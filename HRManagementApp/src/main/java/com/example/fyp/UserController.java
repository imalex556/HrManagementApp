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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Controller
public class UserController {

    @Autowired
    private AuthenticationManager authenticationManager;

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
            model.addAttribute("error", "Invalid email or password.");
        }

        return "login";
    }

    @GetMapping("/welcome")
    public String welcome(Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.isAuthenticated()) {
            String email = authentication.getName();

            Firestore db = FirestoreClient.getFirestore();
            DocumentSnapshot userSnapshot = db.collection("users").whereEqualTo("email", email).get().get().getDocuments().get(0);

            if (userSnapshot.exists()) {
                String role = userSnapshot.getString("role");

                List<QueryDocumentSnapshot> notifications = db.collection("notifications")
                        .whereEqualTo("email", email)
                        .whereEqualTo("read", false)
                        .orderBy("timestamp", com.google.cloud.firestore.Query.Direction.DESCENDING)
                        .get()
                        .get()
                        .getDocuments();

                model.addAttribute("notifications", notifications);

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
        db.collection("notifications").document(notificationId).update("read", true);
        return "redirect:/notifications";
    }

    @PostMapping("/deleteNotification")
    public String deleteNotification(@RequestParam String notificationId) throws ExecutionException, InterruptedException {
        Firestore db = FirestoreClient.getFirestore();
        db.collection("notifications").document(notificationId).delete();
        return "redirect:/notifications";
    }

    @GetMapping("/profile")
    public String showProfile(Model model) throws ExecutionException, InterruptedException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

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
}