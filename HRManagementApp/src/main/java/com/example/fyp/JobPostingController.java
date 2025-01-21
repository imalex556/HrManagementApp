package com.example.fyp;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;
import org.knowm.xchart.PieChart;
import org.knowm.xchart.PieChartBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Controller
public class JobPostingController {

    private static final Logger logger = Logger.getLogger(JobPostingController.class.getName());

    @Autowired
    private Firestore firestore;

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

            // Encode the CV file to Base64
            String encodedCv = encodeFileToBase64(cv);

            // Save the application details along with the encoded CV
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

            // Sort applications by match percentage in descending order
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
                applicationData.put("status", "Shortlisted"); // Update status
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

                // Update status before deleting the application
                Map<String, Object> applicationData = applicationSnapshot.getData();
                applicationData.put("status", "Rejected");
                firestore.collection("applications").document(applicationId).set(applicationData);

                // Add a notification for the user
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
                        .whereEqualTo("shortlisted", true) // Filter for shortlisted candidates
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
                candidateStatuses.put(jobTitle, candidates); // Use job title instead of job ID
            }
        }

        // Pagination logic
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
}