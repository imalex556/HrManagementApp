package com.example.fyp;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.logging.Logger;

@Configuration
public class FirebaseConfig {

    private static final Logger logger = Logger.getLogger(FirebaseConfig.class.getName());

    @Bean
    public FirebaseApp firebaseApp() throws IOException {
        FileInputStream serviceAccount = new FileInputStream("C:\\Users\\Alex\\OneDrive - Technological University Dublin\\Desktop\\Final Year Project\\spring-tool-suite\\HRManagementApp\\src\\main\\resources\\config\\HRManagementApp-firebase-adminsdk.json"); 
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build();
        FirebaseApp app = FirebaseApp.initializeApp(options);
        logger.info("FirebaseApp initialized: " + app.getName());
        return app;
    }

    @Bean
    public Firestore firestore(FirebaseApp firebaseApp) {
        Firestore firestore = FirestoreClient.getFirestore(firebaseApp);
        logger.info("Firestore initialized");
        return firestore;
    }
}