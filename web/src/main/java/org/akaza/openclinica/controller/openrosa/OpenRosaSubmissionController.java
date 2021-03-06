package org.akaza.openclinica.controller.openrosa;

import java.io.File;
import java.io.IOException;
import java.util.*;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import org.akaza.openclinica.bean.core.Status;
import org.akaza.openclinica.bean.managestudy.StudySubjectBean;
import org.akaza.openclinica.bean.rule.FileProperties;
import org.akaza.openclinica.control.submit.UploadFileServlet;
import org.akaza.openclinica.dao.core.CoreResources;
import org.akaza.openclinica.dao.hibernate.StudyDao;
import org.akaza.openclinica.dao.hibernate.StudyParameterValueDao;
import org.akaza.openclinica.dao.hibernate.UserAccountDao;
import org.akaza.openclinica.domain.datamap.Study;
import org.akaza.openclinica.domain.datamap.StudyParameterValue;
import org.akaza.openclinica.domain.user.UserAccount;
import org.akaza.openclinica.exception.OpenClinicaSystemException;
import org.akaza.openclinica.i18n.core.LocaleResolver;
import org.akaza.openclinica.service.pmanage.ParticipantPortalRegistrar;
import org.akaza.openclinica.web.pform.PFormCache;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.validation.DataBinder;
import org.springframework.validation.Errors;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.support.StandardMultipartHttpServletRequest;

@Controller
@RequestMapping(value = "/openrosa")
public class OpenRosaSubmissionController {

    @Autowired
    ServletContext context;

    @Autowired
    private OpenRosaSubmissionService openRosaSubmissionService;

    @Autowired
    private StudyDao studyDao;

    @Autowired
    private StudyParameterValueDao studyParameterValueDao;

    @Autowired
    private UserAccountDao userAccountDao;

    @Autowired
    PformSubmissionNotificationService notifier;

    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
    public static final String FORM_CONTEXT = "ecid";

    /**
     * @api {post} /pages/api/v1/editform/:studyOid/submission Submit form data
     * @apiName doSubmission
     * @apiPermission admin
     * @apiVersion 3.8.0
     * @apiParam {String} studyOid Study Oid.
     * @apiParam {String} ecid Key that will be used to look up subject context information while processing submission.
     * @apiGroup Form
     * @apiDescription Submits the data from a completed form.
     */

    @RequestMapping(value = "/{studyOID}/submission", method = RequestMethod.POST)
    public ResponseEntity<String> doSubmission(HttpServletRequest request, HttpServletResponse response,
            @PathVariable("studyOID") String studyOID, @RequestParam(FORM_CONTEXT) String ecid) {

        logger.info("Processing xform submission.");
        HashMap<String, String> subjectContext = null;
        Locale locale = LocaleResolver.getLocale(request);

        DataBinder dataBinder = new DataBinder(null);
        Errors errors = dataBinder.getBindingResult();
        Study study = studyDao.findByOcOID(studyOID);
        String requestBody=null;

        HashMap<String,String> map = new HashMap();
        ArrayList <HashMap> listOfUploadFilePaths = new ArrayList();

        try {
            // Verify Study is allowed to submit
            if (!mayProceed(study)) {
                logger.info("Submissions to the study not allowed.  Aborting submission.");
                return new ResponseEntity<String>(HttpStatus.NOT_ACCEPTABLE);
            }
            if (ServletFileUpload.isMultipartContent(request)) {
                String dir = getAttachedFilePath(studyOID);
                FileProperties fileProperties= new FileProperties();
                DiskFileItemFactory factory = new DiskFileItemFactory();
                ServletFileUpload upload = new ServletFileUpload(factory);
                upload.setFileSizeMax(fileProperties.getFileSizeMax());
                List<FileItem> items = upload.parseRequest(request);
                for (FileItem item : items) {
                    if (item.getContentType() != null && !item.getFieldName().equals("xml_submission_file") ) {
                        if (!new File(dir).exists()) new File(dir).mkdirs();

                        File file = processUploadedFile(item, dir);
                        map.put(item.getFieldName(), file.getPath());

                    } else if (item.getFieldName().equals("xml_submission_file")) {
                        requestBody = item.getString("UTF-8");
                    }
                }
                listOfUploadFilePaths.add(map);
            } else  {
                requestBody = IOUtils.toString(request.getInputStream(), "UTF-8");
            }

            // Load user context from ecid
            PFormCache cache = PFormCache.getInstance(context);
            subjectContext = cache.getSubjectContext(ecid);

            // Execute save as Hibernate transaction to avoid partial imports
            openRosaSubmissionService.processRequest(study, subjectContext, requestBody, errors, locale ,
                    listOfUploadFilePaths, SubmissionContainer.FieldRequestTypeEnum.FORM_FIELD);

        } catch (Exception e) {
            logger.error("Exception while processing xform submission.");
            logger.error(e.getMessage());
            logger.error(ExceptionUtils.getStackTrace(e));

            if (errors.hasErrors()) {
                // Send a failure response
                logger.info("Submission caused internal error.  Sending error response.");
                return new ResponseEntity<String>(HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }

        if (!errors.hasErrors()) {
            // JsonLog submission with Participate
            if (isParticipantSubmission(subjectContext)) notifier.notify(studyOID, subjectContext);
            logger.info("Completed xform submission. Sending successful response");
            String responseMessage = "<OpenRosaResponse xmlns=\"http://openrosa.org/http/response\">" + "<message>success</message>" + "</OpenRosaResponse>";
            return new ResponseEntity<String>(responseMessage, HttpStatus.CREATED);
        } else {
            logger.info("Submission contained errors. Sending error response");
            return new ResponseEntity<String>(HttpStatus.NOT_ACCEPTABLE);
        }
    }

    /**
     * @api {post} /pages/api/v2/editform/:studyOid/fieldsubmission Submit form data
     * @apiName doSubmission
     * @apiPermission admin
     * @apiVersion 3.8.0
     * @apiParam {String} studyOid Study Oid.
     * @apiParam {String} ecid Key that will be used to look up subject context information while processing submission.
     * @apiGroup Form
     * @apiDescription Submits the data from a completed form.
     */

    @RequestMapping(value = "/{studyOID}/fieldsubmission", method = RequestMethod.POST)
    public ResponseEntity<String> doFieldSubmission(HttpServletRequest request, HttpServletResponse response,
            @PathVariable("studyOID") String studyOID, @RequestParam(FORM_CONTEXT) String ecid) {

        long millis = System.currentTimeMillis();

        logger.info("Processing xform field submission.");
        HashMap<String, String> subjectContext = null;
        Locale locale = LocaleResolver.getLocale(request);

        DataBinder dataBinder = new DataBinder(null);
        Errors errors = dataBinder.getBindingResult();
        Study study = studyDao.findByOcOID(studyOID);
        String requestBody=null;
        String instanceId = null;
        HashMap<String,String> map = new HashMap();
        ArrayList <HashMap> listOfUploadFilePaths = new ArrayList();

        try {
            // Verify Study is allowed to submit
            if (!mayProceed(study)) {
                logger.info("Field Submissions to the study not allowed.  Aborting field submission.");
                return new ResponseEntity<String>(HttpStatus.NOT_ACCEPTABLE);
            }
            if (ServletFileUpload.isMultipartContent(request)) {
                String dir = getAttachedFilePath(studyOID);
                FileProperties fileProperties= new FileProperties();
                DiskFileItemFactory factory = new DiskFileItemFactory();
                ServletFileUpload upload = new ServletFileUpload(factory);
                upload.setFileSizeMax(fileProperties.getFileSizeMax());
                List<FileItem> items = upload.parseRequest(request);
                for (FileItem item : items) {
                    if (item.getFieldName().equals("instance_id")) {
                        instanceId = item.getString();
                    } else if (item.getFieldName().equals("xml_submission_fragment_file")) {
                        requestBody = item.getString("UTF-8");
                    } else if (item.getContentType() != null) {
                        if (!new File(dir).exists()) new File(dir).mkdirs();

                        File file = processUploadedFile(item, dir);
                        map.put(item.getFieldName(), file.getPath());

                    }
                }
                listOfUploadFilePaths.add(map);
            }
            if (instanceId == null)  {
                logger.info("Field Submissions to the study not allowed without a valid instanceId.  Aborting field submission.");
                return new ResponseEntity<String>(HttpStatus.NOT_ACCEPTABLE);
            }

            // Load user context from ecid
            PFormCache cache = PFormCache.getInstance(context);
            subjectContext = cache.getSubjectContext(ecid);

            // Execute save as Hibernate transaction to avoid partial imports
            openRosaSubmissionService.processFieldSubmissionRequest(study, subjectContext, instanceId, requestBody, errors,
                    locale ,listOfUploadFilePaths, SubmissionContainer.FieldRequestTypeEnum.FORM_FIELD);

        } catch (Exception e) {
            logger.error("Exception while processing xform submission.");
            logger.error(e.getMessage());
            logger.error(ExceptionUtils.getStackTrace(e));

            if (!errors.hasErrors()) {
                // Send a failure response
                logger.info("Submission caused internal error.  Sending error response.");
                return new ResponseEntity<String>(HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }

        if (!errors.hasErrors()) {
            // JsonLog submission with Participate
            if (isParticipantSubmission(subjectContext)) notifier.notify(studyOID, subjectContext);
            logger.info("Completed xform field submission. Sending successful response");
            String responseMessage = "<OpenRosaResponse xmlns=\"http://openrosa.org/http/response\">" + "<message>success</message>" + "</OpenRosaResponse>";
            long endMillis = System.currentTimeMillis();
            logger.info("Total time *********** " + (endMillis - millis));
            return new ResponseEntity<String>(responseMessage, HttpStatus.CREATED);
        } else {
            logger.info("Field Submission contained errors. Sending error response");
            return new ResponseEntity<String>(HttpStatus.NOT_ACCEPTABLE);
        }
    }

    /**
     * @api {post} /pages/api/v2/editform/:studyOid/fieldsubmission Submit form data
     * @apiName doSubmission
     * @apiPermission admin
     * @apiVersion 3.8.0
     * @apiParam {String} studyOid Study Oid.
     * @apiParam {String} ecid Key that will be used to look up subject context information while processing submission.
     * @apiGroup Form
     * @apiDescription Submits the data from a completed form.
     */

    @RequestMapping(value = "/{studyOID}/fieldsubmission", method = RequestMethod.DELETE)
    public ResponseEntity<String> doFieldDeletion(HttpServletRequest request, HttpServletResponse response,
            @PathVariable("studyOID") String studyOID, @RequestParam(FORM_CONTEXT) String ecid) {

        logger.info("Processing xform field deletion.");
        HashMap<String, String> subjectContext = null;
        Locale locale = LocaleResolver.getLocale(request);

        DataBinder dataBinder = new DataBinder(null);
        Errors errors = dataBinder.getBindingResult();
        Study study = studyDao.findByOcOID(studyOID);
        String requestBody=null;
        String instanceId = null;
        HashMap<String,String> map = new HashMap();
        ArrayList <HashMap> listOfUploadFilePaths = new ArrayList();

        try {
            // Verify Study is allowed to submit
            if (!mayProceed(study)) {
                logger.info("Field Deletions to the study not allowed.  Aborting field submission.");
                return new ResponseEntity<String>(HttpStatus.NOT_ACCEPTABLE);
            }

            String dir = getAttachedFilePath(studyOID);
            FileProperties fileProperties= new FileProperties();
            DiskFileItemFactory factory = new DiskFileItemFactory();
            ServletFileUpload upload = new ServletFileUpload(factory);
            upload.setFileSizeMax(fileProperties.getFileSizeMax());
            List<FileItem> items = upload.parseRequest(request);
            for (FileItem item : items) {
                if (item.getFieldName().equals("instance_id")) {
                    instanceId = item.getString();
                } else if (item.getFieldName().equals("xml_submission_fragment_file")) {
                    requestBody = item.getString("UTF-8");
                } else if (item.getContentType() != null) {
                    if (!new File(dir).exists()) new File(dir).mkdirs();

                    File file = processUploadedFile(item, dir);
                    map.put(item.getFieldName(), file.getPath());

                }
            }
            listOfUploadFilePaths.add(map);
            if (instanceId == null)  {
                logger.info("Field Submissions to the study not allowed without a valid instanceId.  Aborting field submission.");
                return new ResponseEntity<String>(HttpStatus.NOT_ACCEPTABLE);
            }

            // Load user context from ecid
            PFormCache cache = PFormCache.getInstance(context);
            subjectContext = cache.getSubjectContext(ecid);

            // Execute save as Hibernate transaction to avoid partial imports
            openRosaSubmissionService.processFieldSubmissionRequest(study, subjectContext, instanceId, requestBody,
                    errors, locale , listOfUploadFilePaths, SubmissionContainer.FieldRequestTypeEnum.DELETE_FIELD);

        } catch (Exception e) {
            logger.error("Exception while processing xform submission.");
            logger.error(e.getMessage());
            logger.error(ExceptionUtils.getStackTrace(e));

            if (!errors.hasErrors()) {
                // Send a failure response
                logger.info("Submission caused internal error.  Sending error response.");
                return new ResponseEntity<String>(HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }

        if (!errors.hasErrors()) {
            // JsonLog submission with Participate
            if (isParticipantSubmission(subjectContext)) notifier.notify(studyOID, subjectContext);
            logger.info("Completed xform field submission. Sending successful response");
            String responseMessage = "<OpenRosaResponse xmlns=\"http://openrosa.org/http/response\">" + "<message>success</message>" + "</OpenRosaResponse>";
            return new ResponseEntity<String>(responseMessage, HttpStatus.CREATED);
        } else {
            logger.info("Field Submission contained errors. Sending error response");
            return new ResponseEntity<String>(HttpStatus.NOT_ACCEPTABLE);
        }
    }

    private boolean isParticipantSubmission(HashMap<String, String> subjectContext) {
        boolean isParticipant = true;
        String userAccountId = subjectContext.get("userAccountID");
        if (StringUtils.isNotEmpty(userAccountId)) {
            UserAccount user = userAccountDao.findByUserId(Integer.valueOf(userAccountId));
            // All Participants have a '.' in the user name.  Non-participant user creation does not allow a '.' in the user name.
            if (user != null && !user.getUserName().contains(".")) return false;
        }
        return isParticipant;
    }

    private Study getParentStudy(Study childStudy) {
        Study parentStudy = childStudy.getStudy();
        if (parentStudy != null && parentStudy.getStudyId() > 0)
            return parentStudy;
        else
            return childStudy;
    }


    private boolean mayProceed(Study study) throws Exception {
        return mayProceed(study, null);
    }

    private boolean mayProceed(Study childStudy, StudySubjectBean ssBean) throws Exception {
        boolean accessPermission = false;
        ParticipantPortalRegistrar participantPortalRegistrar= new ParticipantPortalRegistrar();
        Study study = getParentStudy(childStudy);
        StudyParameterValue pStatus = studyParameterValueDao.findByStudyIdParameter(study.getStudyId(), "participantPortal");

        // ACTIVE, PENDING, or INACTIVE
        String pManageStatus = participantPortalRegistrar.getRegistrationStatus(childStudy.getOc_oid()).toString();

        // enabled or disabled
        String participateStatus = pStatus.getValue().toString();

        // available, pending, frozen, or locked
        String studyStatus = study.getStatus().getName().toString();

        if (ssBean == null) {
            logger.info("pManageStatus: " + pManageStatus + "  participantStatus: " + participateStatus + "   studyStatus: " + studyStatus);
            if (participateStatus.equalsIgnoreCase("enabled") && studyStatus.equalsIgnoreCase("available") && pManageStatus.equalsIgnoreCase("ACTIVE"))
                accessPermission = true;
        } else {
            logger.info("pManageStatus: " + pManageStatus + "  participantStatus: " + participateStatus + "   studyStatus: " + studyStatus
                    + "  studySubjectStatus: " + ssBean.getStatus().getName());
            //TODO:  Disabled pManage status check for OC16 conference.  Re-enable after.
            //if (participateStatus.equalsIgnoreCase("enabled") && studyStatus.equalsIgnoreCase("available") && pManageStatus.equalsIgnoreCase("ACTIVE")
            if (participateStatus.equalsIgnoreCase("enabled") && studyStatus.equalsIgnoreCase("available")
                    && ssBean.getStatus() == Status.AVAILABLE)
                accessPermission = true;
        }
        return accessPermission;
    }

    public static String getAttachedFilePath(String studyOid) {
        String attachedFilePath = CoreResources.getField("attached_file_location");
        if (attachedFilePath == null || attachedFilePath.length() <= 0) {
            attachedFilePath = CoreResources.getField("filePath") + "attached_files" + File.separator + studyOid + File.separator;
        } else {
            attachedFilePath += studyOid + File.separator;
        }
        return attachedFilePath;
    }

    private File processUploadedFile(FileItem item, String dirToSaveUploadedFileIn) {
        dirToSaveUploadedFileIn = dirToSaveUploadedFileIn == null ? System.getProperty("java.io.tmpdir") : dirToSaveUploadedFileIn;
        String fileName = item.getName();
        // Some browsers IE 6,7 getName returns the whole path
        int startIndex = fileName.lastIndexOf('\\');
        if (startIndex != -1) {
            fileName = fileName.substring(startIndex + 1, fileName.length());
        }

        File uploadedFile = new File(dirToSaveUploadedFileIn + File.separator + fileName);
        try {
            uploadedFile = new UploadFileServlet().new OCFileRename().rename(uploadedFile, item.getInputStream());
        } catch (IOException e) {
            throw new OpenClinicaSystemException(e.getMessage());
        }

        try {
            item.write(uploadedFile);
        } catch (Exception e) {
            throw new OpenClinicaSystemException(e.getMessage());
        }
        return uploadedFile;
    }
}
