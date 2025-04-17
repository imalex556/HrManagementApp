package com.example.fyp;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class EmailService {

    private static final Logger logger = Logger.getLogger(EmailService.class.getName());

    @Autowired
    private JavaMailSender mailSender;
    
    @Autowired(required = false)
    private TemplateEngine templateEngine;

    public void sendEmail(String to, String subject, String content) {
        sendEmail(to, subject, "Candidate", content);
    }

    public void sendEmail(String to, String subject, String candidateName, String content) {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
        
        try {
            helper.setFrom("noreply@yourhrsystem.com");
            helper.setTo(to);
            helper.setSubject(subject);

            if (templateEngine != null) {
                try {
                    Context context = new Context();
                    context.setVariable("subject", subject);
                    context.setVariable("candidateName", candidateName);
                    context.setVariable("content", content);
                    String htmlContent = templateEngine.process("emails/notification", context);
                    helper.setText(htmlContent, true);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to process email template, falling back to plain text", e);
                    helper.setText(content, false);
                }
            } else {
                helper.setText(content, false);
            }
            
            mailSender.send(message);
        } catch (MessagingException e) {
            logger.log(Level.SEVERE, "Failed to send email", e);
        }
    }
}