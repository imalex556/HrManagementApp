package com.example.fyp;

import com.itextpdf.text.*;
import com.itextpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class OfferLetterService {
    
    public byte[] generateOfferLetter(String candidateName, String jobTitle, 
                                    String jobLocation, double salary) throws DocumentException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, outputStream);
        
        document.open();
        
        // Add title
        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
        Paragraph title = new Paragraph("OFFER LETTER", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(20);
        document.add(title);
        
        // Add date
        Font normalFont = FontFactory.getFont(FontFactory.HELVETICA, 12);
        Paragraph date = new Paragraph("Date: " + LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy")), normalFont);
        date.setSpacingAfter(20);
        document.add(date);
        
        // Add recipient
        Paragraph recipient = new Paragraph("Dear " + candidateName + ",\n\n", normalFont);
        document.add(recipient);
        
        // Add body
        String bodyText = "We are pleased to offer you the position of " + jobTitle + " at our company. " +
                         "This letter outlines the terms of your employment.\n\n" +
                         "Position: " + jobTitle + "\n" +
                         "Location: " + jobLocation + "\n" +
                         "Salary: $" + String.format("%,.2f", salary) + " per year\n\n" +
                         "Your employment is expected to begin on " + 
                         LocalDate.now().plusWeeks(2).format(DateTimeFormatter.ofPattern("MMMM dd, yyyy")) + ".\n\n" +
                         "Please review this offer and respond within 7 days.\n\n" +
                         "We look forward to you joining our team!";
        
        Paragraph body = new Paragraph(bodyText, normalFont);
        body.setSpacingAfter(20);
        document.add(body);
        
        // Add closing
        Paragraph closing = new Paragraph("Sincerely,\n\nHR Team\nCompany Name", normalFont);
        document.add(closing);
        
        document.close();
        return outputStream.toByteArray();
    }
}