package com.example.fyp;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

@Controller
public class UserController {

    private static final Logger logger = Logger.getLogger(UserController.class.getName());

    @Autowired
    private AuthenticationManager authenticationManager;

    private final Firestore firestore;

    public UserController() {
        this.firestore = FirestoreClient.getFirestore();
    }

    @GetMapping("/")
    public String showHomePage() {
        return "index";
    }

    @GetMapping("/signup")
    public String showSignupForm() {
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(
            @RequestParam String email,
            @RequestParam String password,
            @RequestParam String name,
            @RequestParam String role,
            Model model
    ) {
        try {
            Firestore db = FirestoreClient.getFirestore();

            boolean emailExists = !db.collection("users")
                    .whereEqualTo("email", email)
                    .get()
                    .get()
                    .isEmpty();

            if (emailExists) {
                model.addAttribute("message", "Email is already registered. Please use a different email.");
                return "signup";
            }

            UserRecord.CreateRequest request = new UserRecord.CreateRequest()
                    .setEmail(email)
                    .setPassword(password)
                    .setDisplayName(name);
            UserRecord userRecord = FirebaseAuth.getInstance().createUser(request);

            Map<String, Object> userData = new HashMap<>();
            userData.put("uid", userRecord.getUid());
            userData.put("email", email);
            userData.put("name", name);
            userData.put("role", role);
            userData.put("password", password);
            db.collection("users").document(userRecord.getUid()).set(userData).get();

            model.addAttribute("message", "Signup successful! Please login.");
            return "login";

        } catch (FirebaseAuthException | ExecutionException | InterruptedException e) {
            logger.severe("Signup failed: " + e.getMessage());
            model.addAttribute("error", "Signup failed. Please try again later.");
            return "signup";
        }
    }

    @GetMapping("/login")
    public String showLoginForm() {
        return "login";
    }

    @PostMapping("/login")
    public String login(
            @RequestParam String email,
            @RequestParam String password,
            Model model
    ) {
        try {
            FirebaseAuth auth = FirebaseAuth.getInstance();
            UserRecord userRecord = auth.getUserByEmail(email);

            Firestore db = FirestoreClient.getFirestore();
            DocumentSnapshot userSnapshot = db.collection("users").document(userRecord.getUid()).get().get();

            if (userSnapshot.exists()) {
                String role = userSnapshot.getString("role");
                String springSecurityRole = "ROLE_" + role;

                List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList(springSecurityRole);

                Authentication authentication = new UsernamePasswordAuthenticationToken(email, password, authorities);

                SecurityContextHolder.getContext().setAuthentication(authentication);

                if ("HR_STAFF".equalsIgnoreCase(role)) {
                    return "redirect:/welcome_hr";
                } else {
                    return "redirect:/welcome_user";
                }
            } else {
                model.addAttribute("error", "User data not found. Please try again.");
            }
        } catch (FirebaseAuthException | InterruptedException | ExecutionException e) {
            logger.severe("Login failed: " + e.getMessage());
            model.addAttribute("error", "Invalid email or password.");
        }

        return "login";
    }

    @GetMapping("/welcome")
    public String welcome(Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.isAuthenticated()) {
            String email = authentication.getName();
            logger.info("Authenticated user: " + email);

            Firestore db = FirestoreClient.getFirestore();
            DocumentSnapshot userSnapshot = db.collection("users").whereEqualTo("email", email).get().get().getDocuments().get(0);

            if (userSnapshot.exists()) {
                String role = userSnapshot.getString("role");
                logger.info("User role: " + role);

                List<QueryDocumentSnapshot> notifications = db.collection("notifications")
                        .whereEqualTo("email", email)
                        .whereEqualTo("read", false)
                        .orderBy("timestamp", com.google.cloud.firestore.Query.Direction.DESCENDING)
                        .get()
                        .get()
                        .getDocuments();

                model.addAttribute("notifications", notifications);

                // Check if the user has an application in the "Personality Test" stage
                List<QueryDocumentSnapshot> applications = db.collection("applications")
                        .whereEqualTo("email", email)
                        .whereEqualTo("status", "Personality Test")
                        .get()
                        .get()
                        .getDocuments();

                boolean showPersonalityTestLink = !applications.isEmpty();
                model.addAttribute("showPersonalityTestLink", showPersonalityTestLink);

                if ("HR_STAFF".equals(role)) {
                    return "welcome_hr";
                } else {
                    return "welcome_user";
                }
            } else {
                model.addAttribute("error", "User data not found.");
                return "login";
            }
        } else {
            return "login";
        }
    }

    @GetMapping("/notifications")
    public String viewNotifications(Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        logger.info("Fetching notifications for user: " + email);

        Firestore db = FirestoreClient.getFirestore();
        List<QueryDocumentSnapshot> notifications = db.collection("notifications")
                .whereEqualTo("email", email)
                .orderBy("timestamp", com.google.cloud.firestore.Query.Direction.DESCENDING)
                .get()
                .get()
                .getDocuments();

        model.addAttribute("notifications", notifications);
        return "notifications";
    }

    @PostMapping("/markAsRead")
    public String markAsRead(@RequestParam String notificationId) throws ExecutionException, InterruptedException {
        Firestore db = FirestoreClient.getFirestore();
        logger.info("Marking notification as read: " + notificationId);
        db.collection("notifications").document(notificationId).update("read", true);
        return "redirect:/notifications";
    }

    @PostMapping("/deleteNotification")
    public String deleteNotification(@RequestParam String notificationId) throws ExecutionException, InterruptedException {
        Firestore db = FirestoreClient.getFirestore();
        logger.info("Deleting notification: " + notificationId);
        db.collection("notifications").document(notificationId).delete();
        return "redirect:/notifications";
    }

    @GetMapping("/profile")
    public String showProfile(Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        logger.info("Fetching profile for user: " + email);

        Firestore db = FirestoreClient.getFirestore();
        DocumentSnapshot userSnapshot = db.collection("users").whereEqualTo("email", email).get().get().getDocuments().get(0);

        if (userSnapshot.exists()) {
            User user = userSnapshot.toObject(User.class);
            model.addAttribute("user", user);
            return "profile";
        } else {
            model.addAttribute("error", "User data not found.");
            return "login";
        }
    }

    @PostMapping("/updateProfile")
    public String updateProfile(@RequestParam String name, @RequestParam String email, Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String currentEmail = authentication.getName();
        logger.info("Updating profile for user: " + currentEmail);

        Firestore db = FirestoreClient.getFirestore();
        DocumentSnapshot userSnapshot = db.collection("users").whereEqualTo("email", currentEmail).get().get().getDocuments().get(0);

        if (userSnapshot.exists()) {
            String uid = userSnapshot.getId();
            db.collection("users").document(uid).update("name", name, "email", email).get();

            model.addAttribute("message", "Profile updated successfully.");
            return "redirect:/profile";
        } else {
            model.addAttribute("error", "User data not found.");
            return "profile";
        }
    }

    @GetMapping("/takePersonalityTest")
    public String takePersonalityTest(Model model) {
        // Display the personality test form
        return "personality_test";
    }

    @PostMapping("/submitPersonalityTest")
    public String submitPersonalityTest(
            @RequestParam Map<String, String> answers,
            Model model) {
        // Evaluate the personality test
        int score = evaluatePersonalityTest(answers);

        // Determine if the user passes the test
        boolean passed = score >= 15; // Example threshold

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        logger.info("Submitting personality test for user: " + email);

        try {
            List<QueryDocumentSnapshot> applications = firestore.collection("applications")
                    .whereEqualTo("email", email)
                    .whereEqualTo("status", "Personality Test")
                    .get()
                    .get()
                    .getDocuments();

            if (!applications.isEmpty()) {
                DocumentSnapshot applicationSnapshot = applications.get(0);
                String applicationId = applicationSnapshot.getId();
                String jobId = applicationSnapshot.getString("jobId");

                Map<String, Object> applicationData = applicationSnapshot.getData();
                applicationData.put("status", passed ? "Interview Scheduled" : "Personality Test Failed");
                firestore.collection("applications").document(applicationId).set(applicationData);

                // Add a notification for the user
                Map<String, Object> notificationData = new HashMap<>();
                notificationData.put("email", email);
                notificationData.put("message", passed ? "Congratulations! You have passed the personality test and have been scheduled for an interview." : "Unfortunately, you did not pass the personality test.");
                notificationData.put("timestamp", System.currentTimeMillis());
                firestore.collection("notifications").add(notificationData);

                model.addAttribute("message", "Personality test submitted successfully!");
                logger.info("Personality test submitted successfully for user: " + email);
            } else {
                model.addAttribute("error", "No application found for the personality test.");
                logger.warning("No application found for user: " + email);
            }
        } catch (Exception e) {
            model.addAttribute("error", "An error occurred while submitting the personality test. Please try again.");
            logger.severe("Error occurred while submitting personality test for user: " + email + " - " + e.getMessage());
            e.printStackTrace();
        }

        return "redirect:/welcome";
    }

    private int evaluatePersonalityTest(Map<String, String> answers) {
        // Simple evaluation logic for the personality test
        int score = 0;
        for (String answer : answers.values()) {
            try {
                score += Integer.parseInt(answer);
            } catch (NumberFormatException e) {
                // Handle non-numeric values gracefully
                logger.warning("Non-numeric value encountered in personality test answers: " + answer);
                e.printStackTrace();
            }
        }
        return score;
    }

    @GetMapping("/trackApplicationProgress")
    public String trackApplicationProgress(Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        logger.info("Tracking application progress for user: " + email);

        List<QueryDocumentSnapshot> applications = firestore.collection("applications")
                .whereEqualTo("email", email)
                .get()
                .get()
                .getDocuments();

        if (applications != null) {
            logger.info("Applications found: " + applications.size());
            for (QueryDocumentSnapshot app : applications) {
                logger.info("Application: " + app.getData());
            }
        } else {
            logger.info("No applications found.");
        }

        model.addAttribute("applications", applications != null ? applications : new ArrayList<>());
        return "track_application_progress";
    }
    
    @GetMapping("/applicationProgress")
    public String applicationProgress(Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        logger.info("Fetching application progress for user: " + email);

        Firestore db = FirestoreClient.getFirestore();
        List<QueryDocumentSnapshot> applications = db.collection("applications")
                .whereEqualTo("email", email)
                .get()
                .get()
                .getDocuments();

        List<Map<String, Object>> applicationDetails = new ArrayList<>();
        for (QueryDocumentSnapshot application : applications) {
            Map<String, Object> details = new HashMap<>();
            String jobId = application.getString("jobId");
            DocumentSnapshot jobSnapshot = db.collection("jobPostings").document(jobId).get().get();
            String jobTitle = jobSnapshot.exists() ? jobSnapshot.getString("title") : "Unknown";
            String companyName = jobSnapshot.exists() ? jobSnapshot.getString("company") : "Unknown";

            details.put("jobTitle", jobTitle);
            details.put("companyName", companyName);
            String status = application.getString("status");
            if (status == null || status.equals("Shortlisted")) {
                status = "Application Under Review";
            } else if (status.equals("Personality Test")) {
                status = "Next Stage: Personality Test";
            }
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
            }
            details.put("statusClass", statusClass);

            logger.info("Application details: " + details);
            applicationDetails.add(details);
        }

        // Fetch notifications for the user
        List<QueryDocumentSnapshot> notifications = db.collection("notifications")
                .whereEqualTo("email", email)
                .whereEqualTo("read", false)
                .orderBy("timestamp", com.google.cloud.firestore.Query.Direction.DESCENDING)
                .get()
                .get()
                .getDocuments();

        model.addAttribute("applications", applicationDetails);
        model.addAttribute("notifications", notifications);
        return "application_progress";
    }
}