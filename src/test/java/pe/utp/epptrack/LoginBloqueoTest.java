package pe.utp.epptrack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.config.LoginAttemptService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/** Bloqueo temporal de la cuenta tras varios intentos fallidos de inicio de sesión. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LoginBloqueoTest {

    @Autowired MockMvc mvc;
    @Autowired DatosPrueba datos;
    @Autowired LoginAttemptService intentos;

    @BeforeEach
    void preparar() {
        datos.cargar();
        intentos.desbloquear("sup-test");
    }

    @org.junit.jupiter.api.AfterEach
    void limpiar() {
        intentos.desbloquear("sup-test");
    }

    @Test
    void cincoFallosBloqueanLaCuentaAunqueLaClaveSeaCorrecta() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/login").with(csrf()).param("username", "sup-test").param("password", "mala"))
                    .andExpect(redirectedUrl("/login?error"));
        }
        assertThat(intentos.estaBloqueado("sup-test")).isTrue();
        mvc.perform(post("/login").with(csrf()).param("username", "sup-test").param("password", "clave"))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void unIngresoCorrectoReiniciaElContador() throws Exception {
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/login").with(csrf()).param("username", "sup-test").param("password", "mala"));
        }
        mvc.perform(post("/login").with(csrf()).param("username", "sup-test").param("password", "clave"))
                .andExpect(redirectedUrl("/"));
        assertThat(intentos.estaBloqueado("sup-test")).isFalse();
        mvc.perform(post("/login").with(csrf()).param("username", "sup-test").param("password", "mala"));
        assertThat(intentos.estaBloqueado("sup-test")).isFalse();
    }
}
