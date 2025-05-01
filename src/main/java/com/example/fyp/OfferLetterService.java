package com.example.fyp;

import com.itextpdf.text.*;
import com.itextpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class OfferLetterService {
    
    public byte[] generateOfferLetter(String candidateName, String jobTitle, 
                                    String jobLocation, double salary) throws DocumentException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, outputStream);
        
        document.open();

        try {
            Image logo = Image.getInstance("src/main/resources/static/images/logo.png");
            logo.scaleToFit(100, 100);
            logo.setAbsolutePosition(document.right() - 100, document.top() - 50);
            document.add(logo);
        } catch (Exception e) {
            
        }

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
        Paragraph title = new Paragraph("OFFER LETTER", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(15);
        document.add(title);
        
        Font normalFont = FontFactory.getFont(FontFactory.HELVETICA, 12);
        Paragraph date = new Paragraph("Date: " + LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy")), normalFont);
        date.setSpacingAfter(20);
        document.add(date);
        
        Paragraph recipient = new Paragraph("Dear " + candidateName + ",\n\n", normalFont);
        document.add(recipient);
        
        String bodyText = "We are pleased to offer you the position of " + jobTitle + " at our company. " +
                         "This letter outlines the terms of your employment.\n\n" +
                         "Position: " + jobTitle + "\n" +
                         "Location: " + jobLocation + "\n" +
                         "Salary: €" + String.format("%,.2f", salary) + " per year\n\n" +
                         "Your employment is expected to begin on " + 
                         LocalDate.now().plusWeeks(2).format(DateTimeFormatter.ofPattern("MMMM dd, yyyy")) + ".\n\n" +
                         "Please review this offer and respond within 7 days.\n\n" +
                         "We look forward to you joining our team!";
        
        Paragraph body = new Paragraph(bodyText, normalFont);
        body.setSpacingAfter(20);
        document.add(body);
        
        Paragraph closing = new Paragraph("Sincerely,\n\nHR Team\nGoogle", normalFont);
        document.add(closing);
        
        document.close();
        return outputStream.toByteArray();
    }
}