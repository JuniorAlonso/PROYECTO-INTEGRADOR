package pe.utp.epptrack.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import pe.utp.epptrack.repository.UsuarioRepository;

import static pe.utp.epptrack.model.Rol.ADMIN;
import static pe.utp.epptrack.model.Rol.RRHH;
import static pe.utp.epptrack.model.Rol.SUPERVISOR;

/**
 * Autenticación con usuario y contraseña (RF-14) y permisos por perfil (RF-15):
 * <ul>
 *   <li>ADMIN: todo, incluidos catálogo de EPP y matriz.</li>
 *   <li>SUPERVISOR: entregas, alertas, historial y reportes.</li>
 *   <li>RRHH: trabajadores (ingresos, ceses, reingresos) e historial.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/img/**", "/error").permitAll()
                .requestMatchers(HttpMethod.POST, "/epp/**", "/matriz/**").hasRole(ADMIN)
                .requestMatchers("/epp/nuevo", "/epp/*/editar").hasRole(ADMIN)
                .requestMatchers(HttpMethod.POST, "/trabajadores/**").hasAnyRole(ADMIN, RRHH)
                .requestMatchers("/trabajadores/nuevo", "/trabajadores/*/editar").hasAnyRole(ADMIN, RRHH)
                .requestMatchers("/entregas/**", "/alertas/**", "/reportes/**").hasAnyRole(ADMIN, SUPERVISOR)
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .permitAll())
            .logout(logout -> logout
                .logoutSuccessUrl("/login?salir")
                .permitAll());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(UsuarioRepository usuarioRepository) {
        return username -> usuarioRepository.findByNombreUsuario(username)
                .map(u -> User.withUsername(u.getNombreUsuario())
                        .password(u.getContrasenaCifrada())
                        .roles(u.getRol().getNombre())
                        .disabled(!"ACTIVO".equals(u.getEstado()))
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }
}
