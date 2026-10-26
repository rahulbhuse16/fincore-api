package com.fincore.identity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import com.fincore.identity.persistence.UserRepository;

/** Creates the first ADMIN from an environment variable (never from source code). Skipped if unset or already present. */
@Component
class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private final AuthService auth;
    private final UserRepository users;
    private final IdentityConfig.IdentityProperties props;

    AdminBootstrap(AuthService auth, UserRepository users, IdentityConfig.IdentityProperties props) {
        this.auth = auth;
        this.users = users;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String password = props.bootstrapAdminPassword();
        if (password == null || password.isBlank() || users.findByUsername("admin").isPresent()) {
            return;
        }
        auth.createAdmin("admin", "admin@fincore.local", password);
        log.info("Bootstrap ADMIN user 'admin' created from BOOTSTRAP_ADMIN_PASSWORD");
    }
}
