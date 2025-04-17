package com.example.fyp;

import com.example.fyp.OfferLetterService;
import org.springframework.mail.javamail.JavaMailSender;
import java.time.LocalDateTime;
import com.itextpdf.text.DocumentException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.firebase.cloud.FirestoreClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpSession;
import com.google.cloud.firestore.FieldValue;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;
import org.knowm.xchart.PieChart;
import org.knowm.xchart.PieChartBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Controller
public class JobPostingController {

    private static final Logger logger = Logger.getLogger(JobPostingController.class.getName());

    @Autowired
    private Firestore firestore;
    
    @Autowired
    private EmailService emailService;

    @Autowired
    private RestTemplate restTemplate;
    
    @Autowired
    private OfferLetterService offerLetterService;

    @Autowired
    private JavaMailSender mailSender;

    private static final String SENTINO_API_URL = "https://api.sentino.org";
    private static final String SENTINO_API_TOKEN = "4bfbc08bf349c7f501db8405f5150cb65df3fefe";

    @GetMapping("/createJobPosting")
    public String showJobPostingForm() {
        return "job_posting";
    }

    @PostMapping("/createJobPosting")
    public String createJobPosting(
            @RequestParam String title,
            @RequestParam String description,
            @RequestParam String location,
            @RequestParam String company,
            @RequestParam String team,
            Model model
    ) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        if (authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_HR_STAFF"))) {
            Map<String, Object> jobData = new HashMap<>();
            jobData.put("title", title);
            jobData.put("description", description);
            jobData.put("location", location);
            jobData.put("company", company);
            jobData.put("team", team);
            jobData.put("postedBy", email);

            firestore.collection("jobPostings").add(jobData);

            model.addAttribute("message", "Job posted successfully!");
            return "redirect:/welcome";
        } else {
            model.addAttribute("error", "You do not have permission to post jobs.");
            return "login";
        }
    }

    @GetMapping("/viewJobs")
    public String viewJobs(Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        List<QueryDocumentSnapshot> jobPostings = firestore.collection("jobPostings").get().get().getDocuments();
        model.addAttribute("jobPostings", jobPostings);
        model.addAttribute("userEmail", email);
        return "view_jobs";
    }

    @GetMapping("/viewJobsUser")
    public String viewJobsUser(Model model) throws ExecutionException, InterruptedException {
        List<QueryDocumentSnapshot> jobPostings = firestore.collection("jobPostings").get().get().getDocuments();
        model.addAttribute("jobPostings", jobPostings);
        return "view_jobs_user";
    }

    @PostMapping("/deleteJob")
    public String deleteJob(@RequestParam String jobId, Model model) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String email = authentication.getName();

            DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();
            if (jobSnapshot.exists() && email.equals(jobSnapshot.getString("postedBy"))) {
                firestore.collection("jobPostings").document(jobId).delete();
                model.addAttribute("message", "Job deleted successfully!");
            } else {
                model.addAttribute("error", "You do not have permission to delete this job.");
            }
        } catch (Exception e) {
            model.addAttribute("error", "An error occurred while deleting the job. Please try again.");
            e.printStackTrace();
        }

        return "redirect:/viewJobs";
    }

    @GetMapping("/applyJob")
    public String applyJob(@RequestParam String jobId, Model model) {
        model.addAttribute("jobId", jobId);
        return "apply_job";
    }

    @PostMapping("/submitApplication")
    public String submitApplication(@RequestParam String email, @RequestParam String name, @RequestParam("cv") MultipartFile cv, @RequestParam String jobId, Model model) {
        try {
            logger.log(Level.INFO, "Starting application submission for jobId: {0}", jobId);

            String encodedCv = encodeFileToBase64(cv);

            String applicationId = UUID.randomUUID().toString();
            Map<String, Object> applicationData = new HashMap<>();
            applicationData.put("applicationId", applicationId);
            applicationData.put("email", email);
            applicationData.put("name", name);
            applicationData.put("jobId", jobId);
            applicationData.put("cv", encodedCv);

            logger.log(Level.INFO, "Saving application data to Firestore");
            firestore.collection("applications").document(applicationId).set(applicationData);

            model.addAttribute("message", "Application submitted successfully!");
            logger.log(Level.INFO, "Application submitted successfully for jobId: {0}", jobId);
        } catch (Exception e) {
            model.addAttribute("error", "An error occurred while submitting your application. Please try again.");
            logger.log(Level.SEVERE, "Error occurred while submitting application for jobId: " + jobId, e);
        }

        return "redirect:/viewJobsUser";
    }

    private String encodeFileToBase64(MultipartFile file) throws IOException {
        return Base64.getEncoder().encodeToString(file.getBytes());
    }

    private byte[] decodeBase64ToFile(String base64String) {
        return Base64.getDecoder().decode(base64String);
    }

    @GetMapping("/viewApplications")
    public String viewApplications(Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        List<QueryDocumentSnapshot> jobPostings = firestore.collection("jobPostings")
                .whereEqualTo("postedBy", email)
                .get()
                .get()
                .getDocuments();
        model.addAttribute("jobPostings", jobPostings);
        return "view_applications";
    }

    @GetMapping("/viewApplicationsForJob")
    public String viewApplicationsForJob(@RequestParam String jobId, Model model) throws ExecutionException, InterruptedException, IOException {
        DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();

        if (jobSnapshot.exists()) {
            String jobTitle = jobSnapshot.getString("title");
            String jobDescription = jobSnapshot.getString("description");
            List<QueryDocumentSnapshot> applications = firestore.collection("applications")
                    .whereEqualTo("jobId", jobId)
                    .get()
                    .get()
                    .getDocuments();

            List<Map<String, Object>> applicationDetails = new ArrayList<>();
            for (QueryDocumentSnapshot application : applications) {
                String cvText = extractTextFromPdf(decodeBase64ToFile(application.getString("cv")));
                double matchPercentage = calculateMatchPercentage(cvText, jobDescription);

                Map<String, Object> details = new HashMap<>();
                details.put("name", application.getString("name"));
                details.put("email", application.getString("email"));
                details.put("applicationId", application.getId());
                details.put("matchPercentage", matchPercentage);
                applicationDetails.add(details);
            }

            applicationDetails.sort((a, b) -> Double.compare((double) b.get("matchPercentage"), (double) a.get("matchPercentage")));

            model.addAttribute("jobTitle", jobTitle);
            model.addAttribute("applications", applicationDetails);
            model.addAttribute("jobId", jobId);
            return "view_applications_for_job";
        } else {
            model.addAttribute("error", "Job not found.");
            return "view_applications";
        }
    }

    @GetMapping("/downloadCv")
    public ResponseEntity<StreamingResponseBody> downloadCv(@RequestParam String applicationId) throws ExecutionException, InterruptedException {
        DocumentSnapshot applicationSnapshot = firestore.collection("applications").document(applicationId).get().get();

        if (applicationSnapshot.exists()) {
            String encodedCv = applicationSnapshot.getString("cv");
            byte[] cvBytes = decodeBase64ToFile(encodedCv);

            StreamingResponseBody stream = outputStream -> {
                outputStream.write(cvBytes);
                outputStream.flush();
            };

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(ContentDisposition.builder("attachment").filename("cv.pdf").build());

            return new ResponseEntity<>(stream, headers, HttpStatus.OK);
        } else {
            throw new RuntimeException("Application not found.");
        }
    }

    @GetMapping("/viewApplicationDetails")
    public String viewApplicationDetails(@RequestParam String applicationId, @RequestParam String jobId, Model model) throws ExecutionException, InterruptedException, IOException {
        logger.log(Level.INFO, "Fetching application with ID: {0}", applicationId);
        DocumentSnapshot applicationSnapshot = firestore.collection("applications").document(applicationId).get().get();
        logger.log(Level.INFO, "Application data: {0}", applicationSnapshot.getData());

        logger.log(Level.INFO, "Fetching job with ID: {0}", jobId);
        DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();
        logger.log(Level.INFO, "Job data: {0}", jobSnapshot.getData());

        if (applicationSnapshot.exists() && jobSnapshot.exists()) {
            String applicantName = applicationSnapshot.getString("name");
            String applicantEmail = applicationSnapshot.getString("email");
            String encodedCv = applicationSnapshot.getString("cv");
            String jobDescription = jobSnapshot.getString("description");

            byte[] cvBytes = decodeBase64ToFile(encodedCv);
            String cvText = extractTextFromPdf(cvBytes);
            double matchPercentage = calculateMatchPercentage(cvText, jobDescription);

            model.addAttribute("applicantName", applicantName);
            model.addAttribute("applicantEmail", applicantEmail);
            model.addAttribute("matchPercentage", matchPercentage);
            model.addAttribute("applicationId", applicationId);
            model.addAttribute("jobId", jobId);

            return "view_application_details";
        } else {
            logger.log(Level.SEVERE, "Application or Job not found.");
            model.addAttribute("error", "Application or Job not found.");
            return "view_applications_for_job";
        }
    }

    @GetMapping("/generatePieChart")
    public ResponseEntity<InputStreamResource> generatePieChart(@RequestParam String applicationId, @RequestParam String jobId) throws ExecutionException, InterruptedException, IOException {
        logger.log(Level.INFO, "Generating pie chart for application ID: {0} and job ID: {1}", new Object[]{applicationId, jobId});
        DocumentSnapshot applicationSnapshot = firestore.collection("applications").document(applicationId).get().get();
        DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();

        if (applicationSnapshot.exists() && jobSnapshot.exists()) {
            String encodedCv = applicationSnapshot.getString("cv");
            String jobDescription = jobSnapshot.getString("description");

            byte[] cvBytes = decodeBase64ToFile(encodedCv);
            String cvText = extractTextFromPdf(cvBytes);
            double matchPercentage = calculateMatchPercentage(cvText, jobDescription);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            drawPieChart(matchPercentage, outputStream);

            ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(new InputStreamResource(inputStream));
        } else {
            logger.log(Level.SEVERE, "Application or Job not found.");
            throw new RuntimeException("Application or Job not found.");
        }
    }

    private String extractTextFromPdf(byte[] pdfBytes) throws IOException {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            PDFTextStripper pdfStripper = new PDFTextStripper();
            return pdfStripper.getText(document);
        }
    }

    private double calculateMatchPercentage(String cvText, String jobDescription) {
        String[] cvWords = cvText.toLowerCase().split("\\W+");
        String[] jobWords = jobDescription.toLowerCase().split("\\W+");

        Map<String, Integer> cvWordCount = new HashMap<>();
        for (String word : cvWords) {
            cvWordCount.put(word, cvWordCount.getOrDefault(word, 0) + 1);
        }

        Map<String, Integer> jobWordCount = new HashMap<>();
        for (String word : jobWords) {
            jobWordCount.put(word, jobWordCount.getOrDefault(word, 0) + 1);
        }

        int matchCount = 0;
        for (String word : jobWordCount.keySet()) {
            matchCount += Math.min(cvWordCount.getOrDefault(word, 0), jobWordCount.get(word));
        }

        int totalJobWords = jobWords.length;
        return (double) matchCount / totalJobWords * 100;
    }

    private void drawPieChart(double matchPercentage, ByteArrayOutputStream outputStream) throws IOException {
        String[] labels = {"Match", "Mismatch"};
        double[] sizes = {matchPercentage, 100 - matchPercentage};
        Color[] colors = {Color.GREEN, Color.RED};

        PieChart chart = new PieChartBuilder().width(800).height(600).title("CV Match Percentage").build();
        chart.addSeries(labels[0], sizes[0]);
        chart.addSeries(labels[1], sizes[1]);
        chart.getStyler().setSeriesColors(colors);

        BitmapEncoder.saveBitmap(chart, outputStream, BitmapFormat.PNG);
    }

    @PostMapping("/shortlistApplication")
    public String shortlistApplication(@RequestParam String applicationId, @RequestParam String jobId, Model model) {
        try {
            DocumentSnapshot applicationSnapshot = firestore.collection("applications").document(applicationId).get().get();
            if (applicationSnapshot.exists()) {
                Map<String, Object> applicationData = applicationSnapshot.getData();
                applicationData.put("shortlisted", true);
                applicationData.put("status", "Shortlisted");
                firestore.collection("applications").document(applicationId).set(applicationData);
                model.addAttribute("message", "Application shortlisted successfully!");
            } else {
                model.addAttribute("error", "Application not found.");
            }
        } catch (Exception e) {
            model.addAttribute("error", "An error occurred while shortlisting the application. Please try again.");
            e.printStackTrace();
        }
        return "redirect:/viewApplicationsForJob?jobId=" + jobId;
    }

    @PostMapping("/rejectApplication")
    public String rejectApplication(@RequestParam String applicationId, @RequestParam String jobId, Model model) {
        try {
            DocumentSnapshot applicationSnapshot = firestore.collection("applications").document(applicationId).get().get();
            if (applicationSnapshot.exists()) {
                String userEmail = applicationSnapshot.getString("email");
                String jobTitle = firestore.collection("jobPostings").document(jobId).get().get().getString("title");

                Map<String, Object> applicationData = applicationSnapshot.getData();
                applicationData.put("status", "Rejected");
                firestore.collection("applications").document(applicationId).set(applicationData);

                Map<String, Object> notificationData = new HashMap<>();
                notificationData.put("email", userEmail);
                notificationData.put("message", "Your application for the job '" + jobTitle + "' has been rejected.");
                notificationData.put("timestamp", System.currentTimeMillis());
                firestore.collection("notifications").add(notificationData);

                model.addAttribute("message", "Application rejected successfully!");
            } else {
                model.addAttribute("error", "Application not found.");
            }
        } catch (Exception e) {
            model.addAttribute("error", "An error occurred while rejecting the application. Please try again.");
            e.printStackTrace();
        }
        return "redirect:/viewApplicationsForJob?jobId=" + jobId;
    }

    @GetMapping("/viewShortlistedApplications")
    public String viewShortlistedApplications(@RequestParam String jobId, Model model) throws ExecutionException, InterruptedException {
        DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();
        if (jobSnapshot.exists()) {
            String jobTitle = jobSnapshot.getString("title");
            List<QueryDocumentSnapshot> applications = firestore.collection("applications")
                    .whereEqualTo("jobId", jobId)
                    .whereEqualTo("shortlisted", true)
                    .get()
                    .get()
                    .getDocuments();
            model.addAttribute("jobTitle", jobTitle);
            model.addAttribute("applications", applications);
            model.addAttribute("jobId", jobId);
            return "view_shortlisted_applications";
        } else {
            model.addAttribute("error", "Job not found.");
            return "view_applications";
        }
    }

    @GetMapping("/selectJobForShortlistedApplications")
    public String selectJobForShortlistedApplications(Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        List<QueryDocumentSnapshot> jobPostings = firestore.collection("jobPostings")
                .whereEqualTo("postedBy", email)
                .get()
                .get()
                .getDocuments();
        model.addAttribute("jobPostings", jobPostings);
        return "select_job_for_shortlisted_applications";
    }

    @GetMapping("/candidateTrackingDashboard")
    public String candidateTrackingDashboard(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size,
            @RequestParam(required = false) String selectedJob,
            Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        List<QueryDocumentSnapshot> jobPostings = firestore.collection("jobPostings")
                .whereEqualTo("postedBy", email)
                .get()
                .get()
                .getDocuments();
        Map<String, List<Map<String, Object>>> candidateStatuses = new HashMap<>();

        for (QueryDocumentSnapshot job : jobPostings) {
            String jobId = job.getId();
            String jobTitle = job.getString("title");
            if (selectedJob == null || selectedJob.isEmpty() || selectedJob.equals(jobTitle)) {
                List<QueryDocumentSnapshot> applications = firestore.collection("applications")
                        .whereEqualTo("jobId", jobId)
                        .whereEqualTo("shortlisted", true) 
                        .get()
                        .get()
                        .getDocuments();

                List<Map<String, Object>> candidates = new ArrayList<>();
                for (QueryDocumentSnapshot application : applications) {
                    Map<String, Object> candidate = new HashMap<>();
                    candidate.put("name", application.getString("name"));
                    candidate.put("email", application.getString("email"));
                    candidate.put("status", application.getString("status"));
                    candidates.add(candidate);
                }
                candidateStatuses.put(jobTitle, candidates);
            }
        }

        int totalCandidates = candidateStatuses.values().stream().mapToInt(List::size).sum();
        int totalPages = (int) Math.ceil((double) totalCandidates / size);
        int start = page * size;
        int end = Math.min(start + size, totalCandidates);

        List<Map.Entry<String, Map<String, Object>>> paginatedCandidates = candidateStatuses.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(candidate -> Map.entry(entry.getKey(), candidate)))
                .skip(start)
                .limit(size)
                .collect(Collectors.toList());

        model.addAttribute("candidateStatuses", paginatedCandidates);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("jobPostings", jobPostings);
        model.addAttribute("selectedJob", selectedJob);
        return "candidate_tracking_dashboard";
    }

    @PostMapping("/moveToNextStage")
    public String moveToNextStage(@RequestParam String applicationId, @RequestParam String jobId, Model model) {
        try {
            DocumentSnapshot applicationSnapshot = firestore.collection("applications").document(applicationId).get().get();
            if (applicationSnapshot.exists()) {
                String userEmail = applicationSnapshot.getString("email");
                String jobTitle = firestore.collection("jobPostings").document(jobId).get().get().getString("title");

                Map<String, Object> applicationData = applicationSnapshot.getData();
                applicationData.put("status", "Personality Test");
                firestore.collection("applications").document(applicationId).set(applicationData);

                Map<String, Object> notificationData = new HashMap<>();
                notificationData.put("email", userEmail);
                notificationData.put("message", "Congratulations! You have been moved to the next stage for the job '" + jobTitle + "'. Please complete the personality test.");
                notificationData.put("timestamp", System.currentTimeMillis());
                firestore.collection("notifications").add(notificationData);

                model.addAttribute("message", "Candidate moved to the next stage successfully!");
            } else {
                model.addAttribute("error", "Application not found.");
            }
        } catch (Exception e) {
            model.addAttribute("error", "An error occurred while moving the candidate to the next stage. Please try again.");
            e.printStackTrace();
        }
        return "redirect:/viewShortlistedApplications?jobId=" + jobId;
    }


    @GetMapping("/takePersonalityTest/neo")
    public String takePersonalityTest(Model model, HttpSession session) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        List<QueryDocumentSnapshot> notifications = firestore.collection("notifications")
                .whereEqualTo("email", email)
                .whereEqualTo("read", false)
                .orderBy("timestamp", com.google.cloud.firestore.Query.Direction.DESCENDING)
                .get()
                .get()
                .getDocuments();

        model.addAttribute("notifications", notifications);

        String sentinoApiUrl = SENTINO_API_URL + "/api/items/neo";
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Token " + SENTINO_API_TOKEN);
        HttpEntity<String> requestEntity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> responseEntity = restTemplate.exchange(sentinoApiUrl, HttpMethod.GET, requestEntity, String.class);
            String responseBody = responseEntity.getBody();
            logger.info("Sentino API Questions Response: " + responseBody);

            List<String> questions = SentinoUtils.processSentinoQuestionsResponse(responseBody);

            if (questions.size() > 1) {
                questions = questions.subList(0, 1);
            }


            session.setAttribute("questions", questions);
            model.addAttribute("questions", questions);
        } catch (HttpClientErrorException e) {
            logger.severe("Failed to fetch items from Sentino API: " + e.getResponseBodyAsString());
            model.addAttribute("error", "Failed to fetch items from Sentino API. Please try again later.");
        }

        return "personality_test";
    }
    
    private List<String> processSentinoQuestionsResponse(String responseBody) {
        JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
        List<String> questions = new ArrayList<>();
        if (jsonObject.has("items")) {
            JsonArray itemsArray = jsonObject.getAsJsonArray("items");
            for (JsonElement itemElement : itemsArray) {
                questions.add(itemElement.getAsString());
            }
        }
        logger.info("Processed Questions: " + questions);
        return questions;
    }

    @PostMapping("/submitPersonalityTest")
    public String submitPersonalityTest(
        @RequestParam Map<String, String> answers, 
        Model model, 
        HttpSession session) throws InterruptedException, ExecutionException {
        
        logger.info("Starting submitPersonalityTest method");
        answers.remove("_csrf");

        List<String> questions = (List<String>) session.getAttribute("questions");
        if (questions == null || questions.size() < 1) {
            model.addAttribute("error", "Session expired or incomplete test. Please retake the test.");
            return "redirect:/welcome";
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 1; i++) {
            String responseKey = "question" + i;
            if (answers.containsKey(responseKey)) {
                String response = answers.get(responseKey).toLowerCase();
                
                if (!response.matches("strongly agree|agree|slightly agree|neutral|slightly disagree|disagree|strongly disagree")) {
                    logger.severe("Invalid response detected: " + responseKey + "=" + response);
                    model.addAttribute("error", "Invalid response: " + response);
                    return "redirect:/welcome";
                }

                Map<String, Object> item = new HashMap<>();
                item.put("item", questions.get(i));
                item.put("response", response);
                items.add(item);
            }
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("inventories", Collections.singletonList("neo"));
        payload.put("items", items);
        payload.put("lang", "en");

        String sentinoApiUrl = SENTINO_API_URL + "/api/score/items";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + SENTINO_API_TOKEN);
        
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                sentinoApiUrl,
                new HttpEntity<>(new Gson().toJson(payload), headers),
                String.class
            );

            JsonObject jsonResponse = JsonParser.parseString(response.getBody()).getAsJsonObject();
            boolean passed = processSentinoResponse(jsonResponse);

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String email = authentication.getName();
            
            List<QueryDocumentSnapshot> applications = firestore.collection("applications")
                .whereEqualTo("email", email)
                .whereEqualTo("status", "Personality Test")
                .get()
                .get()
                .getDocuments();

            if (!applications.isEmpty()) {
                DocumentSnapshot application = applications.get(0);
                String applicationId = application.getId();
                String jobId = application.getString("jobId");
                String candidateName = application.getString("name");

                DocumentSnapshot job = firestore.collection("jobPostings").document(jobId).get().get();
                String jobTitle = job.getString("title");
                String hrEmail = job.getString("postedBy");

                Map<String, Object> updates = new HashMap<>();
                updates.put("status", passed ? "Interview Stage" : "Rejected");
                updates.put("personalityTestResult", passed ? "Passed" : "Failed");
                firestore.collection("applications").document(applicationId).update(updates);

                String emailSubject = passed ? 
                    "Congratulations! You passed the personality test" : 
                    "Update on your personality test results";
                
                String emailContent = passed ?
                    "We're pleased to inform you that you've passed the personality test for the position: " + jobTitle + ".\n\n" +
                    "Our HR team will contact you shortly regarding the next steps in the hiring process.\n\n" +
                    "Best regards,\n" +
                    "HR Team" :
                    "Dear " + candidateName + ",\n\n" +
                    "Thank you for completing the personality test for the position: " + jobTitle + ".\n\n" +
                    "After careful consideration, we regret to inform you that you did not pass this stage of the hiring process.\n\n" +
                    "We appreciate your time and interest in our company.\n\n";
                
                emailService.sendEmail(email, emailSubject, candidateName, emailContent);

                Map<String, Object> candidateNotification = new HashMap<>();
                candidateNotification.put("email", email);
                candidateNotification.put("message", passed ? 
                    "Congratulations! You passed the personality test for " + jobTitle :
                    "Unfortunately you didn't pass the personality test for " + jobTitle);
                candidateNotification.put("timestamp", System.currentTimeMillis());
                firestore.collection("notifications").add(candidateNotification);

                if (passed) {
                    Map<String, Object> hrNotification = new HashMap<>();
                    hrNotification.put("email", hrEmail);
                    hrNotification.put("message", candidateName + " passed personality test for " + jobTitle);
                    hrNotification.put("timestamp", System.currentTimeMillis());
                    hrNotification.put("type", "personality_test_passed");
                    hrNotification.put("applicationId", applicationId);
                    hrNotification.put("jobId", jobId);
                    hrNotification.put("read", false);
                    firestore.collection("notifications").add(hrNotification);
                }
            }

            model.addAttribute("message", "Personality test submitted successfully!");
            return "redirect:/applicationProgress";
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error processing Sentino response", e);
            model.addAttribute("error", "Error processing test results");
            return "redirect:/welcome";
        }
    }

    private boolean processSentinoResponse(JsonObject jsonResponse) {
        try {
            JsonObject scoring = jsonResponse.getAsJsonObject("scoring").getAsJsonObject("neo");
            double total = 0;
            int count = 0;

            Set<String> invertTraits = Set.of("neuroticism", "disinhibition", "disagreeableness");

            for (Map.Entry<String, JsonElement> entry : scoring.entrySet()) {
                String trait = entry.getKey().toLowerCase();
                double score = entry.getValue().getAsJsonObject().get("score").getAsDouble();
                
                if (invertTraits.contains(trait)) {
                    score = -score;
                }
                
                total += score;
                count++;
            }

            double average = total / count;
            logger.info("Calculated average score: " + average);
            
            return average >= 0.0;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error parsing Sentino response", e);
            return false;
        }
    }

    private void updateApplicationStatus(boolean passed) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        List<QueryDocumentSnapshot> applications = firestore.collection("applications")
            .whereEqualTo("email", email)
            .whereEqualTo("status", "Personality Test")
            .get()
            .get()
            .getDocuments();
        if (!applications.isEmpty()) {
            String applicationId = applications.get(0).getId();
            String jobId = applications.get(0).getString("jobId");
            DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();
            String jobTitle = jobSnapshot.getString("title");
            
            Map<String, Object> updates = new HashMap<>();
            updates.put("status", passed ? "Interview Stage" : "Rejected");
            updates.put("personalityTestResult", passed ? "Passed" : "Failed");
            
            firestore.collection("applications")
                .document(applicationId)
                .update(updates);
            
            logger.info("Updated application status for: " + applicationId);
            
            if (passed) {
                Map<String, Object> notificationData = new HashMap<>();
                notificationData.put("email", email);
                notificationData.put("message", "Congratulations! You have been moved to the interview stage for the job '" + jobTitle + "'.");
                notificationData.put("timestamp", System.currentTimeMillis());
                firestore.collection("notifications").add(notificationData);
            }
        }
    }
    
    @GetMapping("/applicationProgress")
    public String applicationProgress(Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        Firestore db = FirestoreClient.getFirestore();

        logger.info("Loading application progress for user: " + email);

        List<QueryDocumentSnapshot> applications = db.collection("applications")
                .whereEqualTo("email", email)
                .get()
                .get()
                .getDocuments();
        logger.info("Found " + applications.size() + " applications for user: " + email);

        List<Map<String, Object>> applicationDetails = new ArrayList<>();
        for (QueryDocumentSnapshot application : applications) {
            Map<String, Object> details = new HashMap<>();
            String jobId = application.getString("jobId");

            DocumentSnapshot jobSnapshot = db.collection("jobPostings").document(jobId).get().get();
            String jobTitle = jobSnapshot.exists() ? jobSnapshot.getString("title") : "Unknown";
            String companyName = jobSnapshot.exists() ? jobSnapshot.getString("company") : "Unknown";

            logger.info("Job details for application: " + jobTitle + " at " + companyName);

            details.put("jobTitle", jobTitle);
            details.put("companyName", companyName);

            String status = application.getString("status");
            if (status == null || status.equals("Shortlisted")) {
                status = "Application Under Review";
            } else if (status.equals("Personality Test")) {
                status = "Next Stage: Personality Test";
            } else if (status.equals("Interview Scheduled")) {
                details.put("interviewDateTime", application.getString("interviewDateTime"));
                details.put("interviewType", application.getString("interviewType"));
                details.put("interviewLocation", application.getString("interviewLocation"));
                details.put("interviewDetails", application.getString("interviewDetails"));
            }

            logger.info("Application status for job " + jobTitle + ": " + status);

            details.put("status", status);

            String statusClass;
            switch (status) {
                case "Application Under Review":
                    statusClass = "status under-review";
                    break;
                case "Next Stage: Personality Test":
                    statusClass = "status next-stage";
                    break;
                case "Rejected":
                    statusClass = "status rejected";
                    break;
                case "Interview Scheduled":
                    statusClass = "status interview-scheduled";
                    break;
                case "Interview Stage":
                    statusClass = "status interview-stage";
                    break;
                case "Interview Passed":
                    statusClass = "status interview-passed";
                    break;
                case "Interview Failed":
                    statusClass = "status interview-failed";
                    break;
                default:
                    statusClass = "status under-review";
                    break;
            }
            details.put("statusClass", statusClass);

            applicationDetails.add(details);
        }

        List<QueryDocumentSnapshot> notifications = db.collection("notifications")
                .whereEqualTo("email", email)
                .whereEqualTo("read", false)
                .orderBy("timestamp", com.google.cloud.firestore.Query.Direction.DESCENDING)
                .get()
                .get()
                .getDocuments();

        logger.info("Found " + notifications.size() + " unread notifications for user: " + email);

        List<Map<String, Object>> notificationDetails = new ArrayList<>();
        for (QueryDocumentSnapshot notification : notifications) {
            Map<String, Object> notificationData = new HashMap<>();
            notificationData.put("id", notification.getId());
            notificationData.put("message", notification.getString("message"));
            notificationData.put("read", notification.getBoolean("read"));

            Long timestampMillis = notification.getLong("timestamp");
            if (timestampMillis != null) {
                Date timestampDate = new Date(timestampMillis);
                notificationData.put("timestamp", timestampDate);
            } else {
                notificationData.put("timestamp", null);
            }

            notificationDetails.add(notificationData);
        }

        model.addAttribute("applications", applicationDetails);
        model.addAttribute("notifications", notificationDetails);

        return "application_progress";
    }
    
    @GetMapping("/scheduleInterview")
    public String showScheduleInterviewForm(
            @RequestParam String applicationId,
            @RequestParam String jobId,
            Model model) throws ExecutionException, InterruptedException {
        
        DocumentSnapshot applicationSnapshot = firestore.collection("applications").document(applicationId).get().get();
        DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();
        
        if (applicationSnapshot.exists() && jobSnapshot.exists()) {
            model.addAttribute("applicationId", applicationId);
            model.addAttribute("jobId", jobId);
            model.addAttribute("candidateName", applicationSnapshot.getString("name"));
            model.addAttribute("jobTitle", jobSnapshot.getString("title"));
            return "schedule_interview";
        } else {
            model.addAttribute("error", "Application or Job not found.");
            return "redirect:/viewShortlistedApplications?jobId=" + jobId;
        }
    }

    @PostMapping("/scheduleInterview")
    public String scheduleInterview(
            @RequestParam String applicationId,
            @RequestParam String jobId,
            @RequestParam String interviewDateTime,
            @RequestParam String interviewType,
            @RequestParam String location,
            Model model) throws ExecutionException, InterruptedException {
        
        try {
            DocumentSnapshot applicationSnapshot = firestore.collection("applications").document(applicationId).get().get();
            DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();
            
            if (applicationSnapshot.exists() && jobSnapshot.exists()) {
                String candidateEmail = applicationSnapshot.getString("email");
                String candidateName = applicationSnapshot.getString("name");
                String jobTitle = jobSnapshot.getString("title");

                LocalDateTime dateTime = LocalDateTime.parse(interviewDateTime);
                
                DateTimeFormatter displayFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a");
                String formattedDate = dateTime.format(displayFormatter);

                Map<String, Object> applicationData = applicationSnapshot.getData();
                applicationData.put("status", "Interview Scheduled");
                applicationData.put("interviewDateTime", interviewDateTime);
                applicationData.put("interviewType", interviewType);
                applicationData.put("interviewLocation", location);
                firestore.collection("applications").document(applicationId).set(applicationData);

                Map<String, Object> interviewData = new HashMap<>();
                interviewData.put("jobId", jobId);
                interviewData.put("candidateEmail", candidateEmail);
                interviewData.put("candidateName", candidateName);
                interviewData.put("interviewDateTime", interviewDateTime);
                interviewData.put("interviewType", interviewType);
                interviewData.put("location", location);
                interviewData.put("status", "Scheduled");
                interviewData.put("createdAt", FieldValue.serverTimestamp());
                firestore.collection("interviews").add(interviewData);

                String emailSubject = "Interview Scheduled: " + jobTitle;
                String emailContent = "We are pleased to invite you for an interview for the position: " + jobTitle + ".\n\n" +
                    "Interview Details:\n" +
                    "Date & Time: " + formattedDate + "\n" +
                    "Type: " + interviewType + "\n";
                   
                
                if ("In-Person".equals(interviewType)) {
                    emailContent += "Location: " + location + "\n";
                } else {
                    emailContent += "Meeting Link: Will be sent to you prior to the interview\n";
                }
                
                emailContent += "\nPlease confirm your availability by replying to this email.\n\n";
            
                emailService.sendEmail(candidateEmail, emailSubject, candidateName, emailContent);

                String notificationMessage = String.format(
                    "Your interview for '%s' is scheduled for %s (%s). %s",
                    jobTitle,
                    formattedDate,
                    interviewType,
                    interviewType.equals("In-Person") ? "Location: " + location : ""
                );

                Map<String, Object> notificationData = new HashMap<>();
                notificationData.put("email", candidateEmail);
                notificationData.put("message", notificationMessage.trim());
                notificationData.put("timestamp", System.currentTimeMillis());
                notificationData.put("type", "interview_scheduled");
                notificationData.put("applicationId", applicationId);
                notificationData.put("jobId", jobId);
                notificationData.put("candidateName", candidateName);
                notificationData.put("jobTitle", jobTitle);
                
                firestore.collection("notifications").add(notificationData);
                
                model.addAttribute("message", "Interview scheduled successfully!");
            } else {
                model.addAttribute("error", "Application or Job not found.");
            }
        } catch (DateTimeParseException e) {
            logger.log(Level.SEVERE, "Invalid date format received: " + interviewDateTime, e);
            model.addAttribute("error", "Invalid date/time format. Please use the date picker.");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error scheduling interview", e);
            model.addAttribute("error", "Error scheduling interview: " + e.getMessage());
        }
        
        return "redirect:/viewShortlistedApplications?jobId=" + jobId;
    }

    @PostMapping("/acceptInterview")
    public String acceptInterview(
        @RequestParam String notificationId,
        @RequestParam String applicationId,
        Model model) throws ExecutionException, InterruptedException {

        logger.info("Notification ID: " + notificationId);
        logger.info("Application ID: " + applicationId);

        try {
            logger.info("Starting acceptInterview - notificationId: " + notificationId + ", applicationId: " + applicationId);

            if (notificationId == null || notificationId.isEmpty() || applicationId == null || applicationId.isEmpty()) {
                model.addAttribute("error", "Invalid notification or application ID");
                return "redirect:/applicationProgress";
            }

            DocumentReference applicationRef = firestore.collection("applications").document(applicationId);
            DocumentSnapshot applicationSnapshot = applicationRef.get().get();

            if (!applicationSnapshot.exists()) {
                model.addAttribute("error", "Application not found.");
                return "redirect:/applicationProgress";
            }

            Map<String, Object> applicationUpdate = new HashMap<>();
            applicationUpdate.put("status", "Interview Accepted");
            applicationRef.update(applicationUpdate).get();

            String jobId = applicationSnapshot.getString("jobId");
            if (jobId == null || jobId.isEmpty()) {
                model.addAttribute("error", "Job ID not found in application.");
                return "redirect:/applicationProgress";
            }

            DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();
            if (!jobSnapshot.exists()) {
                model.addAttribute("error", "Job not found.");
                return "redirect:/applicationProgress";
            }

            String hrEmail = jobSnapshot.getString("postedBy");
            String candidateName = applicationSnapshot.getString("name");
            String jobTitle = jobSnapshot.getString("title");
            String interviewDateTime = applicationSnapshot.getString("interviewDateTime");

            Map<String, Object> hrNotification = new HashMap<>();
            hrNotification.put("email", hrEmail);
            hrNotification.put("message", candidateName + " has accepted the interview for '" + jobTitle + "' scheduled for " + interviewDateTime);
            hrNotification.put("timestamp", System.currentTimeMillis());
            hrNotification.put("type", "interview_response");
            hrNotification.put("applicationId", applicationId);
            hrNotification.put("jobId", jobId);
            hrNotification.put("read", false);
            firestore.collection("notifications").add(hrNotification).get();

            logger.info("HR notification created successfully for HR: " + hrEmail);

            DocumentReference notificationRef = firestore.collection("notifications").document(notificationId);
            notificationRef.update("read", true).get();

            model.addAttribute("message", "Interview accepted successfully!");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error accepting interview", e);
            model.addAttribute("error", "Failed to accept interview. Please try again.");
        }

        return "redirect:/applicationProgress";
    }


    @PostMapping("/declineInterview")
    public String declineInterview(
            @RequestParam String notificationId,
            @RequestParam String applicationId,
            Model model) throws ExecutionException, InterruptedException {
        
        try {
            logger.log(Level.INFO, "Starting declineInterview - notificationId: {0}, applicationId: {1}", 
                new Object[]{notificationId, applicationId});

            if (applicationId == null || applicationId.isEmpty()) {
                logger.log(Level.SEVERE, "Empty applicationId received");
                model.addAttribute("error", "Invalid application ID");
                return "redirect:/applicationProgress";
            }

            DocumentReference applicationRef = firestore.collection("applications").document(applicationId);
            DocumentSnapshot applicationSnapshot = applicationRef.get().get();
            
            if (!applicationSnapshot.exists()) {
                logger.log(Level.SEVERE, "Application not found for ID: {0}", applicationId);
                model.addAttribute("error", "Application not found");
                return "redirect:/applicationProgress";
            }

            String jobId = applicationSnapshot.getString("jobId");
            if (jobId == null || jobId.isEmpty()) {
                logger.log(Level.SEVERE, "No jobId found in application: {0}", applicationId);
                model.addAttribute("error", "Job ID not found in application");
                return "redirect:/applicationProgress";
            }

            DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();
            
            if (!jobSnapshot.exists()) {
                logger.log(Level.SEVERE, "Job not found for ID: {0}", jobId);
                model.addAttribute("error", "Job not found");
                return "redirect:/applicationProgress";
            }

            String hrEmail = jobSnapshot.getString("postedBy");
            String candidateName = applicationSnapshot.getString("name");
            String jobTitle = jobSnapshot.getString("title");
            String interviewDateTime = applicationSnapshot.getString("interviewDateTime");

            if (hrEmail == null || hrEmail.isEmpty()) {
                logger.log(Level.SEVERE, "No HR email found for job: {0}", jobId);
                model.addAttribute("error", "HR email not found for this job");
                return "redirect:/applicationProgress";
            }

            Map<String, Object> applicationUpdate = new HashMap<>();
            applicationUpdate.put("status", "Interview Declined");
            applicationRef.update(applicationUpdate).get();

            Map<String, Object> hrNotification = new HashMap<>();
            hrNotification.put("email", hrEmail);
            hrNotification.put("message", candidateName + " has declined the scheduled interview for " + 
                interviewDateTime + " for " + jobTitle + " role.");
            hrNotification.put("timestamp", FieldValue.serverTimestamp());
            hrNotification.put("read", false);
            hrNotification.put("type", "interview_response");
            hrNotification.put("applicationId", applicationId);
            hrNotification.put("jobId", jobId);
            hrNotification.put("candidateName", candidateName);
            hrNotification.put("jobTitle", jobTitle);

            firestore.collection("notifications").add(hrNotification).get();

            List<QueryDocumentSnapshot> interviews = firestore.collection("interviews")
                .whereEqualTo("jobId", jobId)
                .whereEqualTo("candidateEmail", applicationSnapshot.getString("email"))
                .whereEqualTo("status", "Scheduled")
                .get().get().getDocuments();
            
            for (QueryDocumentSnapshot interview : interviews) {
                Map<String, Object> interviewUpdate = new HashMap<>();
                interviewUpdate.put("status", "Declined by Candidate");
                interviewUpdate.put("interviewDateTime", null);
                firestore.collection("interviews").document(interview.getId()).update(interviewUpdate).get();
            }

            if (notificationId != null && !notificationId.isEmpty()) {
                try {
                    DocumentReference notificationRef = firestore.collection("notifications").document(notificationId);
                    DocumentSnapshot notificationSnapshot = notificationRef.get().get();
                    if (notificationSnapshot.exists()) {
                        notificationRef.update("read", true).get();
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Could not mark notification {0} as read", notificationId);
                }
            }

            model.addAttribute("message", "Interview declined successfully!");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error declining interview", e);
            model.addAttribute("error", "Failed to decline interview. Please try again.");
        }

        return "redirect:/applicationProgress";
    }
    
    @GetMapping("/viewInterviews")
    public String viewInterviews(
            @RequestParam(required = false) String show,
            Model model) throws ExecutionException, InterruptedException {
        
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String hrEmail = auth.getName();

        List<QueryDocumentSnapshot> jobs = firestore.collection("jobPostings")
                .whereEqualTo("postedBy", hrEmail)
                .get().get().getDocuments();
        model.addAttribute("jobs", jobs);
        
        if (show == null) {
            return "interviews";
        }

        List<String> jobIds = jobs.stream().map(DocumentSnapshot::getId).collect(Collectors.toList());
        
        if ("completed".equals(show)) {
            List<Map<String, Object>> completedInterviews = new ArrayList<>();
            for (String jobId : jobIds) {
                List<QueryDocumentSnapshot> interviews = firestore.collection("interviews")
                        .whereEqualTo("jobId", jobId)
                        .whereIn("status", Arrays.asList("Passed", "Failed"))
                        .get().get().getDocuments();
                
                for (QueryDocumentSnapshot interview : interviews) {
                    Map<String, Object> interviewData = new HashMap<>();
                    interviewData.put("candidateName", interview.getString("candidateName"));
                    interviewData.put("jobTitle", getJobTitle(interview.getString("jobId")));
                    interviewData.put("interviewDateTime", interview.getString("interviewDateTime"));
                    interviewData.put("status", interview.getString("status"));
                    completedInterviews.add(interviewData);
                }
            }
            model.addAttribute("completedInterviews", completedInterviews);
        } 
        else if ("all".equals(show)) {
            List<Map<String, Object>> allInterviews = new ArrayList<>();
            for (String jobId : jobIds) {
                List<QueryDocumentSnapshot> interviews = firestore.collection("interviews")
                        .whereEqualTo("jobId", jobId)
                        .get().get().getDocuments();
                
                for (QueryDocumentSnapshot interview : interviews) {
                    Map<String, Object> interviewData = new HashMap<>();
                    interviewData.put("candidateName", interview.getString("candidateName"));
                    interviewData.put("jobTitle", getJobTitle(interview.getString("jobId")));
                    interviewData.put("interviewDateTime", interview.getString("interviewDateTime"));
                    interviewData.put("status", interview.getString("status"));
                    allInterviews.add(interviewData);
                }
            }
            model.addAttribute("allInterviews", allInterviews);
        }
        
        return "interviews";
    }

    @GetMapping("/viewJobInterviews")
    public String viewJobInterviews(
            @RequestParam String jobId,
            Model model) throws ExecutionException, InterruptedException {
        
        DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();
        if (!jobSnapshot.exists()) {
            model.addAttribute("error", "Job not found");
            return "interviews";
        }
        
        model.addAttribute("jobTitle", jobSnapshot.getString("title"));
        model.addAttribute("jobId", jobId);

        List<QueryDocumentSnapshot> scheduledInterviews = firestore.collection("interviews")
                .whereEqualTo("jobId", jobId)
                .whereEqualTo("status", "Scheduled")
                .get().get().getDocuments();
        model.addAttribute("interviews", scheduledInterviews);
        
        return "interviews";
    }

    private String getJobTitle(String jobId) throws InterruptedException, ExecutionException {
        DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();
        return jobSnapshot.exists() ? jobSnapshot.getString("title") : "Unknown Job";
    }

    @PostMapping("/updateInterviewStatus")
    public String updateInterviewStatus(
            @RequestParam String interviewId,
            @RequestParam String status,
            @RequestParam String jobId,
            Model model) throws ExecutionException, InterruptedException {
        
        try {
            DocumentSnapshot interviewSnapshot = firestore.collection("interviews").document(interviewId).get().get();
            if (interviewSnapshot.exists()) {
                String candidateEmail = interviewSnapshot.getString("candidateEmail");
                String candidateName = interviewSnapshot.getString("candidateName");
                String jobTitle = getJobTitle(jobId);
                String interviewDateTime = interviewSnapshot.getString("interviewDateTime");

                Map<String, Object> updates = new HashMap<>();
                updates.put("status", status);
                updates.put("updatedAt", FieldValue.serverTimestamp());
                firestore.collection("interviews").document(interviewId).update(updates).get();

                String emailSubject = "Interview Results: " + jobTitle;
                String emailContent;
                
                if ("Passed".equals(status)) {
                    emailContent = "Congratulations! You have successfully passed the interview for " + jobTitle + ".\n\n" +
                        "Our HR team will contact you shortly regarding the next steps in the hiring process.\n\n";
                } else {
                    emailContent = "Thank you for participating in the interview process for " + jobTitle + ".\n\n" +
                        "After careful consideration, we regret to inform you that we won't be moving forward with your application at this time.\n\n" +
                        "We appreciate your time and interest in our company.\n\n";
                }
                
                emailService.sendEmail(candidateEmail, emailSubject, candidateName, emailContent);

                List<QueryDocumentSnapshot> applications = firestore.collection("applications")
                        .whereEqualTo("email", candidateEmail)
                        .whereEqualTo("jobId", jobId)
                        .get().get().getDocuments();
                
                if (!applications.isEmpty()) {
                    Map<String, Object> appUpdates = new HashMap<>();
                    appUpdates.put("status", "Interview " + status);
                    firestore.collection("applications").document(applications.get(0).getId()).update(appUpdates).get();
                }
                
                model.addAttribute("message", "Interview status updated successfully!");
            } else {
                model.addAttribute("error", "Interview not found");
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error updating interview status", e);
            model.addAttribute("error", "Failed to update interview status");
        }
        
        return "redirect:/viewJobInterviews?jobId=" + jobId;
    }
    
    @PostMapping("/generateOfferLetter")
    public String generateOfferLetter(
            @RequestParam String interviewId,
            @RequestParam String jobId,
            @RequestParam String candidateEmail,
            @RequestParam double salary,
            Model model) throws ExecutionException, InterruptedException, DocumentException {
        
        try {
            DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();
            List<QueryDocumentSnapshot> applications = firestore.collection("applications")
                    .whereEqualTo("jobId", jobId)
                    .whereEqualTo("email", candidateEmail)
                    .get().get().getDocuments();
            
            if (applications.isEmpty() || !jobSnapshot.exists()) {
                model.addAttribute("error", "Candidate or job not found");
                return "redirect:/viewJobInterviews?jobId=" + jobId;
            }
            
            String candidateName = applications.get(0).getString("name");
            String jobTitle = jobSnapshot.getString("title");
            String jobLocation = jobSnapshot.getString("location");
            
            byte[] offerLetterPdf = offerLetterService.generateOfferLetter(
                    candidateName, jobTitle, jobLocation, salary);
            
            String offerId = UUID.randomUUID().toString();
            Map<String, Object> offerData = new HashMap<>();
            offerData.put("offerId", offerId);
            offerData.put("applicationId", applications.get(0).getId());
            offerData.put("jobId", jobId);
            offerData.put("candidateEmail", candidateEmail);
            offerData.put("candidateName", candidateName);
            offerData.put("jobTitle", jobTitle);
            offerData.put("salary", salary);
            offerData.put("status", "Pending");
            offerData.put("createdAt", FieldValue.serverTimestamp());
            firestore.collection("offers").document(offerId).set(offerData);
            
            String emailSubject = "Job Offer: " + jobTitle;
            String emailContent = "Dear " + candidateName + ",\n\n" +
                    "Congratulations! We are pleased to offer you the position of " + jobTitle + ".\n\n" +
                    "Please find attached your official offer letter. You can review and respond to this offer " +
                    "directly in the application by accepting or declining the offer.\n\n" +
                    "You have 7 days to respond to this offer.\n\n" +
                    "Best regards,\n" +
                    "HR Team";
            
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setTo(candidateEmail);
            helper.setSubject(emailSubject);
            helper.setText(emailContent);
            helper.addAttachment("Offer_Letter_" + jobTitle.replace(" ", "_") + ".pdf", 
                    new ByteArrayResource(offerLetterPdf));
            mailSender.send(message);
            
            Map<String, Object> notificationData = new HashMap<>();
            notificationData.put("email", candidateEmail);
            notificationData.put("message", "You have received a job offer for " + jobTitle);
            notificationData.put("timestamp", System.currentTimeMillis());
            notificationData.put("type", "offer_received");
            notificationData.put("offerId", offerId);
            notificationData.put("read", false);
            firestore.collection("notifications").add(notificationData);
            
            firestore.collection("interviews").document(interviewId)
                    .update("status", "Offer Sent");
            
            model.addAttribute("message", "Offer letter sent successfully!");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error generating offer letter", e);
            model.addAttribute("error", "Failed to generate offer letter: " + e.getMessage());
        }
        
        return "redirect:/viewJobInterviews?jobId=" + jobId;
    }
    
    @PostMapping("/acceptOffer")
    public String acceptOffer(
            @RequestParam String offerId,
            @RequestParam String notificationId,
            Model model) throws ExecutionException, InterruptedException {
        
        try {
            logger.info("Starting offer acceptance - offerId: " + offerId);
            DocumentSnapshot offerSnapshot = firestore.collection("offers").document(offerId).get().get();

            if (!offerSnapshot.exists()) {
                model.addAttribute("error", "Offer not found");
                return "redirect:/applicationProgress";
            }

            firestore.collection("offers").document(offerId)
                    .update("status", "Accepted", "acceptedAt", FieldValue.serverTimestamp());

            String applicationId = offerSnapshot.getString("applicationId");
            String jobId = offerSnapshot.getString("jobId");
            String candidateEmail = offerSnapshot.getString("candidateEmail");
            
            DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();
            String jobTeam = jobSnapshot.getString("team");
            String jobTitle = jobSnapshot.getString("title");

            QuerySnapshot userQuery = firestore.collection("users")
                    .whereEqualTo("email", candidateEmail)
                    .get().get();
            
            if (!userQuery.isEmpty()) {
                DocumentSnapshot userDoc = userQuery.getDocuments().get(0);
                firestore.collection("users").document(userDoc.getId())
                        .update(
                            "role", "NEW_JOINER",
                            "team", jobTeam
                        );
                logger.info("Updated user role and team for: " + candidateEmail);
            }

            firestore.collection("applications").document(applicationId)
                    .update("status", "Offer Accepted");

            String hrEmail = jobSnapshot.getString("postedBy");
            String candidateName = offerSnapshot.getString("candidateName");
            
            Map<String, Object> hrNotification = new HashMap<>();
            hrNotification.put("email", hrEmail);
            hrNotification.put("message", candidateName + " has accepted the offer for " + jobTitle);
            hrNotification.put("timestamp", System.currentTimeMillis());
            hrNotification.put("type", "offer_accepted");
            hrNotification.put("read", false);
            firestore.collection("notifications").add(hrNotification);

            if (notificationId != null && !notificationId.isEmpty()) {
                firestore.collection("notifications").document(notificationId)
                        .update("read", true);
            }

            model.addAttribute("message", "Offer accepted successfully!");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error accepting offer", e);
            model.addAttribute("error", "Failed to accept offer");
        }
        
        return "redirect:/applicationProgress";
    }

    @PostMapping("/declineOffer")
    public String declineOffer(
            @RequestParam String offerId,
            @RequestParam String notificationId,
            Model model) throws ExecutionException, InterruptedException {
        
        try {
            DocumentSnapshot offerSnapshot = firestore.collection("offers").document(offerId).get().get();
            if (!offerSnapshot.exists()) {
                model.addAttribute("error", "Offer not found");
                return "redirect:/applicationProgress";
            }
            
            firestore.collection("offers").document(offerId)
                    .update("status", "Declined",
                            "declinedAt", FieldValue.serverTimestamp());

            String applicationId = offerSnapshot.getString("applicationId");
            firestore.collection("applications").document(applicationId)
                    .update("status", "Offer Declined");
            
            String jobId = offerSnapshot.getString("jobId");
            String candidateName = offerSnapshot.getString("candidateName");
            String jobTitle = offerSnapshot.getString("jobTitle");
            
            DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();
            if (jobSnapshot.exists()) {
                String hrEmail = jobSnapshot.getString("postedBy");
                
                Map<String, Object> hrNotification = new HashMap<>();
                hrNotification.put("email", hrEmail);
                hrNotification.put("message", candidateName + " has declined the offer for " + jobTitle);
                hrNotification.put("timestamp", System.currentTimeMillis());
                hrNotification.put("type", "offer_declined");
                hrNotification.put("read", false);
                firestore.collection("notifications").add(hrNotification);
            }
            
            if (notificationId != null && !notificationId.isEmpty()) {
                firestore.collection("notifications").document(notificationId)
                        .update("read", true);
            }
            
            model.addAttribute("message", "Offer declined successfully!");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error declining offer", e);
            model.addAttribute("error", "Failed to decline offer");
        }
        
        return "redirect:/applicationProgress";
    }
    
    @GetMapping("/probationReview")
    public String probationReview(Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        
        List<QueryDocumentSnapshot> reviews = firestore.collection("probationReviews")
                .whereEqualTo("reviewer", email)
                .get().get().getDocuments();
        
        List<Map<String, Object>> reviewList = new ArrayList<>();
        for (QueryDocumentSnapshot reviewDoc : reviews) {
            Map<String, Object> review = new HashMap<>();
            review.put("joinerName", reviewDoc.getString("joinerName"));
            review.put("review", reviewDoc.getString("review"));
            review.put("timestamp", reviewDoc.getTimestamp("timestamp").toDate());
            reviewList.add(review);
        }
        
        model.addAttribute("reviews", reviewList);
        return "probation_review";
    }
    
    @GetMapping("/selectNewJoinerForReview")
    public String selectNewJoinerForReview(Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        
        try {
            DocumentSnapshot employee = firestore.collection("users")
                    .whereEqualTo("email", email)
                    .get().get().getDocuments().get(0);
            
            if (!employee.exists()) {
                model.addAttribute("error", "User not found");
                return "redirect:/welcome";
            }

            String team = employee.getString("team");
            if (team == null || team.isEmpty()) {
                model.addAttribute("error", "You are not assigned to any team");
                return "redirect:/welcome";
            }

            List<QueryDocumentSnapshot> newJoiners = firestore.collection("users")
                    .whereEqualTo("role", "NEW_JOINER")
                    .whereEqualTo("team", team)
                    .get().get().getDocuments();

            model.addAttribute("newJoiners", newJoiners);
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error fetching new joiners", e);
            model.addAttribute("error", "Error loading new joiners list");
        }
        
        return "select_new_joiner";
    }

    @GetMapping("/leaveReview")
    public String showLeaveReviewForm(
            @RequestParam String joinerId, 
            Model model) throws ExecutionException, InterruptedException {
        
        DocumentSnapshot joiner = firestore.collection("users").document(joinerId).get().get();
        if (joiner.exists()) {
            model.addAttribute("joinerName", joiner.getString("name"));
            model.addAttribute("joinerId", joinerId);
            return "leave_review";
        }
        return "redirect:/probationReview";
    }

    @PostMapping("/submitProbationReview")
    public String submitProbationReview(
            @RequestParam String joinerId,
            @RequestParam String review,
            Model model) throws ExecutionException, InterruptedException {
        
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String reviewerEmail = authentication.getName();
        
        try {
            // Get reviewer details
            DocumentSnapshot reviewerDoc = firestore.collection("users")
                    .whereEqualTo("email", reviewerEmail)
                    .get().get().getDocuments().get(0);
            String reviewerName = reviewerDoc.getString("name");

            // Get joiner details
            DocumentSnapshot joinerDoc = firestore.collection("users").document(joinerId).get().get();
            String joinerName = joinerDoc.getString("name");
            String joinerEmail = joinerDoc.getString("email");
            String joinerTeam = joinerDoc.getString("team");

            // Save review
            Map<String, Object> reviewData = new HashMap<>();
            reviewData.put("joinerId", joinerId);
            reviewData.put("reviewer", reviewerEmail);
            reviewData.put("review", review);
            reviewData.put("timestamp", FieldValue.serverTimestamp());
            firestore.collection("probationReviews").add(reviewData);

            // Find HR to notify
            List<QueryDocumentSnapshot> applications = firestore.collection("applications")
                    .whereEqualTo("email", joinerEmail)
                    .whereEqualTo("status", "Offer Accepted")
                    .get().get().getDocuments();

            if (!applications.isEmpty()) {
                String jobId = applications.get(0).getString("jobId");
                DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();
                
                if (jobSnapshot.exists()) {
                    String hrEmail = jobSnapshot.getString("postedBy");
                    
                    // Create HR notification
                    Map<String, Object> notification = new HashMap<>();
                    notification.put("email", hrEmail);
                    notification.put("message", reviewerName + " has left a probation review for " + joinerName);
                    notification.put("timestamp", System.currentTimeMillis());
                    notification.put("type", "probation_review");
                    firestore.collection("notifications").add(notification);
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error submitting probation review", e);
            model.addAttribute("error", "Error submitting review");
        }
        
        return "redirect:/probationReview";
    }
    
    @GetMapping("/reviewHistory")
    public String reviewHistory(Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        
        List<QueryDocumentSnapshot> reviews = firestore.collection("probationReviews")
                .whereEqualTo("reviewer", email)
                .get().get().getDocuments();
        
        List<Map<String, Object>> reviewList = new ArrayList<>();
        for (QueryDocumentSnapshot reviewDoc : reviews) {
            String joinerId = reviewDoc.getString("joinerId");
            DocumentSnapshot joinerDoc = firestore.collection("users").document(joinerId).get().get();
            
            Map<String, Object> review = new HashMap<>();
            if(joinerDoc.exists()) {
                review.put("joinerName", joinerDoc.getString("name"));
                review.put("joinerEmail", joinerDoc.getString("email"));
            }
            review.put("review", reviewDoc.getString("review"));
            review.put("timestamp", reviewDoc.getTimestamp("timestamp").toDate());
            reviewList.add(review);
        }
        
        model.addAttribute("reviews", reviewList);
        return "review_history";
    }
    
    @GetMapping("/viewProbationReviews")
    public String viewProbationReviews(Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String hrEmail = authentication.getName();
        
        // Get all jobs posted by this HR
        List<QueryDocumentSnapshot> jobs = firestore.collection("jobPostings")
                .whereEqualTo("postedBy", hrEmail)
                .get().get().getDocuments();
        
        // Convert Set to List for Firestore query
        Set<String> teams = jobs.stream()
                .map(job -> job.getString("team"))
                .collect(Collectors.toSet());
        
        List<String> teamList = new ArrayList<>(teams); // Convert Set to List
        
        List<QueryDocumentSnapshot> joiners = new ArrayList<>();
        if (!teamList.isEmpty()) {
            joiners = firestore.collection("users")
                    .whereEqualTo("role", "NEW_JOINER")
                    .whereIn("team", teamList) // Now using List instead of Set
                    .get().get().getDocuments();
        }
        
        model.addAttribute("newJoiners", joiners);
        return "hr_select_joiner";
    }

    @GetMapping("/viewJoinerReviews")
    public String viewJoinerReviews(
            @RequestParam String joinerId,
            Model model) throws ExecutionException, InterruptedException {
        
        DocumentSnapshot joinerDoc = firestore.collection("users").document(joinerId).get().get();
        
        Map<String, Object> joinerDetails = new HashMap<>();
        joinerDetails.put("name", joinerDoc.getString("name"));
        joinerDetails.put("email", joinerDoc.getString("email"));
        joinerDetails.put("team", joinerDoc.getString("team"));
        joinerDetails.put("role", joinerDoc.getString("role"));
        
        List<Map<String, Object>> reviews = new ArrayList<>();
        List<QueryDocumentSnapshot> reviewDocs = firestore.collection("probationReviews")
                .whereEqualTo("joinerId", joinerId)
                .get().get().getDocuments();
        
        for (QueryDocumentSnapshot doc : reviewDocs) {
            Map<String, Object> review = new HashMap<>();
            DocumentSnapshot reviewer = firestore.collection("users")
                    .whereEqualTo("email", doc.getString("reviewer"))
                    .get().get().getDocuments().get(0);
            
            review.put("reviewerName", reviewer.getString("name"));
            review.put("content", doc.getString("review"));
            review.put("date", doc.getTimestamp("timestamp").toDate());
            reviews.add(review);
        }
        
        model.addAttribute("joiner", joinerDetails);
        model.addAttribute("reviews", reviews);
        return "joiner_review_details";
    }
}