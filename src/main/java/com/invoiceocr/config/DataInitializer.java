package com.invoiceocr.config;

import com.invoiceocr.domain.AppUser;
import com.invoiceocr.domain.Role;
import com.invoiceocr.repo.AppUserRepository;
import com.invoiceocr.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final AppProperties properties;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SystemConfigService configService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path storageRoot = Path.of(properties.getStorage().getRoot()).toAbsolutePath().normalize();
        Files.createDirectories(storageRoot.resolve("files"));
        Files.createDirectories(storageRoot.resolve("ocr-results"));
        configService.seedDefaults();

        if (!properties.getSeed().isEnabled()) {
            return;
        }
        seedUser("admin", "系统管理员", Role.ADMIN, properties.getSeed().getAdminPassword());
        seedUser("reviewer", "财务复核员", Role.REVIEWER, properties.getSeed().getReviewerPassword());
        seedUser("submitter", "提交人员", Role.SUBMITTER, properties.getSeed().getSubmitterPassword());
        seedUser("auditor", "审计查看者", Role.AUDITOR, properties.getSeed().getAuditorPassword());

        log.info("Initial demo users: admin / reviewer / submitter / auditor (see README for passwords)");
        log.info("Storage root: {}", storageRoot);
    }

    private void seedUser(String username, String displayName, Role role, String rawPassword) {
        if (userRepository.findByUsername(username).isPresent()) {
            return;
        }
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("Seeded user '{}' with role {}", username, role);
    }
}
