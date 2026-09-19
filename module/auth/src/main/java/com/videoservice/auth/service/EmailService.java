package com.videoservice.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    public void sendConfirmationCode(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("Подтверждение регистрации VideoService");
        message.setText("Ваш код подтверждения: " + code + "\nКод действителен 15 минут.");
        mailSender.send(message);
    }
}