package myconext.security;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.saml.key.SimpleKey;
import org.springframework.security.saml.provider.config.RotatingKeys;
import org.springframework.security.saml.provider.identity.config.ExternalServiceProviderConfiguration;
import org.springframework.security.saml.provider.identity.config.SamlIdentityProviderSecurityConfiguration;
import org.springframework.security.saml.provider.identity.config.SamlIdentityProviderSecurityDsl;
import org.springframework.security.saml.saml2.metadata.NameId;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider;
import org.springframework.security.web.csrf.CsrfFilter;
import myconext.config.BeanConfig;
import myconext.repository.UserRepository;
import myconext.shibboleth.ShibbolethPreAuthenticatedProcessingFilter;
import myconext.shibboleth.ShibbolethUserDetailService;
import myconext.shibboleth.mock.MockShibbolethFilter;

import java.io.IOException;
import java.nio.charset.Charset;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static java.util.Arrays.asList;
import static org.springframework.security.saml.saml2.signature.AlgorithmMethod.RSA_SHA512;
import static org.springframework.security.saml.saml2.signature.DigestMethod.SHA512;

@EnableWebSecurity
public class SecurityConfiguration {

    private static final Log LOG = LogFactory.getLog(SecurityConfiguration.class);

    @Configuration
    @Order(1)
    public static class SamlSecurity extends SamlIdentityProviderSecurityConfiguration {
        private final Resource privateKeyPath;
        private final Resource certificatePath;
        private final String spEntityId;
        private final String spMetaDataUrl;
        private final String idpEntityId;
        private BeanConfig beanConfig;

        public SamlSecurity(BeanConfig beanConfig,
                            @Value("${private_key_path}") Resource privateKeyPath,
                            @Value("${certificate_path}") Resource certificatePath,
                            @Value("${idp_entity_id}") String idpEntityId,
                            @Value("${sp_entity_id}") String spEntityId,
                            @Value("${sp_entity_metadata_url}") String spMetaDataUrl) {
            super("/saml/guest-idp/", beanConfig);
            this.beanConfig = beanConfig;
            this.privateKeyPath = privateKeyPath;
            this.certificatePath = certificatePath;
            this.idpEntityId = idpEntityId;
            this.spEntityId = spEntityId;
            this.spMetaDataUrl = spMetaDataUrl;
        }

        @Override
        protected void configure(HttpSecurity http) throws Exception {
            super.configure(http);

            String prefix = getPrefix();
            SamlIdentityProviderSecurityDsl configurer = new GuestIdentityProviderDsl(beanConfig);

            http.apply(configurer)
                    .prefix(prefix)
                    .useStandardFilters(false)
                    .entityId(idpEntityId)
                    .alias("guest-idp")
                    .singleLogout(false)
                    .signMetadata(true)
                    .signatureAlgorithms(RSA_SHA512, SHA512)
                    .nameIds(asList(NameId.EMAIL))
                    .rotatingKeys(getKeys())
                    .serviceProvider(
                            new ExternalServiceProviderConfiguration()
                                    .setAlias(spEntityId)
                                    .setMetadata(spMetaDataUrl)
                                    .setSkipSslValidation(false)
                    )
            ;
        }

        private RotatingKeys getKeys() throws IOException, NoSuchAlgorithmException {
            String privateKey;
            String certificate;
            if (this.privateKeyPath.exists() && this.certificatePath.exists()) {
                privateKey = read(this.privateKeyPath);
                certificate = read(this.certificatePath);
            } else {
                String[] keys = generateKeys();
                privateKey = keys[0];
                certificate = keys[1];
            }
            return new RotatingKeys()
                    .setActive(
                            new SimpleKey()
                                    .setName("idp-signing-key")
                                    .setPrivateKey(privateKey)
                                    //to prevent null-pointer in SamlKeyStoreProvider
                                    .setPassphrase("")
                                    .setCertificate(certificate)
                    );
        }

        private String read(Resource resource) throws IOException {
            LOG.info("Reading resource: " + resource.getFilename());
            return IOUtils.toString(resource.getInputStream(), Charset.defaultCharset());
        }

        private String[] generateKeys() throws NoSuchAlgorithmException {
            LOG.info("Generating public / private key pair for SAML trusted proxy");
            Base64.Encoder encoder = Base64.getEncoder();
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();

            String privateKey = "-----BEGIN RSA PRIVATE KEY-----\n";
            privateKey += encoder.encodeToString(kp.getPrivate().getEncoded());
            privateKey += "\n-----END RSA PRIVATE KEY-----\n";

            String publicKey = "-----BEGIN RSA PUBLIC KEY-----\n";
            publicKey += encoder.encodeToString(kp.getPublic().getEncoded());
            publicKey += "\n-----END RSA PUBLIC KEY-----\n";

            return new String[]{privateKey, publicKey};
        }

    }


    @Autowired
    public void configureGlobal(AuthenticationManagerBuilder auth) throws Exception {
        //because Autowired this will end up in the global ProviderManager
        PreAuthenticatedAuthenticationProvider authenticationProvider = new PreAuthenticatedAuthenticationProvider();
        authenticationProvider.setPreAuthenticatedUserDetailsService(new ShibbolethUserDetailService());
        auth.authenticationProvider(authenticationProvider);
    }

    @Order
    @Configuration
    public static class InternalSecurityConfigurationAdapter extends WebSecurityConfigurerAdapter {


        private Environment environment;

        private UserRepository userRepository;

        public InternalSecurityConfigurationAdapter(Environment environment, UserRepository userRepository) {
            this.environment = environment;
            this.userRepository = userRepository;
        }

        @Override
        public void configure(WebSecurity web) throws Exception {
            web.ignoring().antMatchers("/actuator/**", "/myconext/api/idp/**");
        }

        @Override
        protected void configure(HttpSecurity http) throws Exception {
            http
                    .requestMatchers().antMatchers("/myconext/api/sp/**", "/startSSO")
                    .and()
                    .sessionManagement()
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .and()
                    .csrf()
                    .requireCsrfProtectionMatcher(new CsrfProtectionMatcher())
                    .and()
                    .addFilterAfter(new CsrfTokenResponseHeaderBindingFilter(), CsrfFilter.class)
                    .addFilterBefore(
                            new ShibbolethPreAuthenticatedProcessingFilter(authenticationManagerBean(), userRepository),
                            AbstractPreAuthenticatedProcessingFilter.class
                    )
                    .authorizeRequests()
                    .antMatchers("/**").hasRole("GUEST");

            if (environment.acceptsProfiles(Profiles.of("test", "dev"))) {
                //we can't use @Profile, because we need to add it before the real filter
                http.csrf().disable();
                http.addFilterBefore(new MockShibbolethFilter(environment), ShibbolethPreAuthenticatedProcessingFilter.class);
            }
        }
    }

}
