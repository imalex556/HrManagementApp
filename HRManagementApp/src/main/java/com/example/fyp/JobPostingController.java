package com.example.fyp;

import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.firebase.cloud.FirestoreClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpSession;

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
    private RestTemplate restTemplate;

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

            if (questions.size() > 20) {
                questions = questions.subList(0, 20);
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
        HttpSession session
    ) throws InterruptedException, ExecutionException {
        logger.info("Starting submitPersonalityTest method");
        answers.remove("_csrf");

        // Get questions from session
        List<String> questions = (List<String>) session.getAttribute("questions");
        if (questions == null || questions.size() < 20) {
            model.addAttribute("error", "Session expired or incomplete test. Please retake the test.");
            return "redirect:/welcome";
        }

        // Prepare items for Sentino API
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
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

        // Prepare payload for Sentino API
        Map<String, Object> payload = new HashMap<>();
        payload.put("inventories", Collections.singletonList("neo"));
        payload.put("items", items);
        payload.put("lang", "en");

        String sentinoApiUrl = SENTINO_API_URL + "/api/score/items";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + SENTINO_API_TOKEN);
        
        try {
            // Send request to Sentino API
            ResponseEntity<String> response = restTemplate.postForEntity(
                sentinoApiUrl,
                new HttpEntity<>(new Gson().toJson(payload), headers),
                String.class
            );

            // Process response
            JsonObject jsonResponse = JsonParser.parseString(response.getBody()).getAsJsonObject();
            boolean passed = processSentinoResponse(jsonResponse);

            // Update application status and notify HR
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
                
                // Get job details
                DocumentSnapshot job = firestore.collection("jobPostings").document(jobId).get().get();
                String jobTitle = job.getString("title");
                String hrEmail = job.getString("postedBy");

                // Update application status
                Map<String, Object> updates = new HashMap<>();
                updates.put("status", passed ? "Interview Stage" : "Rejected");
                updates.put("personalityTestResult", passed ? "Passed" : "Failed");
                firestore.collection("applications").document(applicationId).update(updates);

                // Notify candidate
                Map<String, Object> candidateNotification = new HashMap<>();
                candidateNotification.put("email", email);
                candidateNotification.put("message", passed ? 
                    "Congratulations! You passed the personality test for " + jobTitle :
                    "Unfortunately you didn't pass the personality test for " + jobTitle);
                candidateNotification.put("timestamp", System.currentTimeMillis());
                firestore.collection("notifications").add(candidateNotification);

                // Notify HR if passed
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
            
            return average >= 0.3;

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
                default:
                    statusClass = "status under-review";
                    break;
                case "Interview Stage":
                    statusClass = "status interview-stage";
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
            Model model) throws ExecutionException, InterruptedException {
        
        DocumentSnapshot applicationSnapshot = firestore.collection("applications").document(applicationId).get().get();
        DocumentSnapshot jobSnapshot = firestore.collection("jobPostings").document(jobId).get().get();
        
        if (applicationSnapshot.exists() && jobSnapshot.exists()) {
            String candidateEmail = applicationSnapshot.getString("email");
            String jobTitle = jobSnapshot.getString("title");

            Map<String, Object> applicationData = applicationSnapshot.getData();
            applicationData.put("status", "Interview Scheduled");
            applicationData.put("interviewDateTime", interviewDateTime);
            firestore.collection("applications").document(applicationId).set(applicationData);

            Map<String, Object> notificationData = new HashMap<>();
            notificationData.put("email", candidateEmail);
            notificationData.put("message", "Your interview for '" + jobTitle + "' is scheduled for " + interviewDateTime);
            notificationData.put("timestamp", System.currentTimeMillis());
            notificationData.put("type", "interview_scheduled");
            notificationData.put("applicationId", applicationId);
            notificationData.put("interviewDateTime", interviewDateTime);
            firestore.collection("notifications").add(notificationData);
            
            model.addAttribute("message", "Interview scheduled successfully!");
        } else {
            model.addAttribute("error", "Application or Job not found.");
        }
        
        return "redirect:/viewShortlistedApplications?jobId=" + jobId;
    }

    @PostMapping("/acceptInterview")
    public String acceptInterview(
            @RequestParam String notificationId,
            @RequestParam String applicationId,
            Model model) throws ExecutionException, InterruptedException {
        
        DocumentSnapshot notificationSnapshot = firestore.collection("notifications").document(notificationId).get().get();
        DocumentSnapshot applicationSnapshot = firestore.collection("applications").document(applicationId).get().get();
        
        if (notificationSnapshot.exists() && applicationSnapshot.exists()) {
            Map<String, Object> applicationData = applicationSnapshot.getData();
            applicationData.put("status", "Interview Accepted");
            firestore.collection("applications").document(applicationId).set(applicationData);

            firestore.collection("notifications").document(notificationId).update("read", true);

            String hrEmail = applicationSnapshot.getString("postedBy");
            String candidateName = applicationSnapshot.getString("name");
            String jobTitle = firestore.collection("jobPostings").document(applicationSnapshot.getString("jobId")).get().get().getString("title");
            String interviewDateTime = applicationSnapshot.getString("interviewDateTime");
            
            Map<String, Object> hrNotificationData = new HashMap<>();
            hrNotificationData.put("email", hrEmail);
            hrNotificationData.put("message", candidateName + " has accepted the interview for '" + jobTitle + "' scheduled for " + interviewDateTime);
            hrNotificationData.put("timestamp", System.currentTimeMillis());
            firestore.collection("notifications").add(hrNotificationData);
            
            model.addAttribute("message", "Interview accepted successfully!");
        } else {
            model.addAttribute("error", "Notification or Application not found.");
        }
        
        return "redirect:/applicationProgress";
    }

    @GetMapping("/rescheduleInterview")
    public String showRescheduleForm(
            @RequestParam String notificationId,
            @RequestParam String applicationId,
            Model model) throws ExecutionException, InterruptedException {
        
        DocumentSnapshot notificationSnapshot = firestore.collection("notifications").document(notificationId).get().get();
        DocumentSnapshot applicationSnapshot = firestore.collection("applications").document(applicationId).get().get();
        
        if (notificationSnapshot.exists() && applicationSnapshot.exists()) {
            model.addAttribute("notificationId", notificationId);
            model.addAttribute("applicationId", applicationId);
            model.addAttribute("jobId", applicationSnapshot.getString("jobId"));
            return "reschedule_request";
        } else {
            model.addAttribute("error", "Notification or Application not found.");
            return "redirect:/applicationProgress";
        }
    }

    @PostMapping("/submitRescheduleRequest")
    public String submitRescheduleRequest(
            @RequestParam String notificationId,
            @RequestParam String applicationId,
            @RequestParam String reason,
            Model model) throws ExecutionException, InterruptedException {
        
        DocumentSnapshot notificationSnapshot = firestore.collection("notifications").document(notificationId).get().get();
        DocumentSnapshot applicationSnapshot = firestore.collection("applications").document(applicationId).get().get();
        
        if (notificationSnapshot.exists() && applicationSnapshot.exists()) {
            firestore.collection("notifications").document(notificationId).update("read", true);
            
            String hrEmail = applicationSnapshot.getString("postedBy");
            String candidateName = applicationSnapshot.getString("name");
            String jobTitle = firestore.collection("jobPostings").document(applicationSnapshot.getString("jobId")).get().get().getString("title");
            
            Map<String, Object> hrNotificationData = new HashMap<>();
            hrNotificationData.put("email", hrEmail);
            hrNotificationData.put("message", candidateName + " requested to reschedule interview for '" + jobTitle + "'. Reason: " + reason);
            hrNotificationData.put("timestamp", System.currentTimeMillis());
            hrNotificationData.put("type", "reschedule_request");
            hrNotificationData.put("applicationId", applicationId);
            firestore.collection("notifications").add(hrNotificationData);
            
            model.addAttribute("message", "Reschedule request submitted successfully!");
        } else {
            model.addAttribute("error", "Notification or Application not found.");
        }
        
        return "redirect:/applicationProgress";
    }
}