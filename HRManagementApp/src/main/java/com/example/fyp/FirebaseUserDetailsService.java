package com.example.fyp;

import com.google.cloud.firestore.Firestore;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Service
public class FirebaseUserDetailsService implements UserDetailsService {

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        try {
            UserRecord userRecord = FirebaseAuth.getInstance().getUserByEmail(email);

            Firestore db = FirestoreClient.getFirestore();
            Map<String, Object> userData = db.collection("users")
                    .document(userRecord.getUid()).get().get().getData();
            String role = (String) userData.get("role");
            String password = (String) userData.get("password");

            String springSecurityRole = "ROLE_" + role;

            List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList(springSecurityRole);

            return new User(email, password, authorities);

        } catch (FirebaseAuthException | ExecutionException | InterruptedException e) {
            throw new UsernameNotFoundException("User not found.", e);
        }
    }
}
