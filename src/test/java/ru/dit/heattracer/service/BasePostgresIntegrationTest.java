package ru.dit.heattracer.service;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Базовый класс для интеграционных тестов с изолированным PostgreSQL (PostGIS + pgRouting).
 *
 * <p>Инкапсулирует типовую инфраструктуру Testcontainers:
 * <ul>
 *   <li>fail-fast проверку доступности Docker с полным сообщением о причине
 *       (вместо безликого {@code ExceptionInInitializerError});</li>
 *   <li>общий контейнер {@code nickblah/pgrouting}: ленивый singleton на всю JVM
 *       (без {@code @Container}, иначе Testcontainers переставлял бы его между классами,
 *       а кешированный Spring-контекст терял бы соединения HikariCP);</li>
 *   <li>проброс JDBC-параметров в Spring через {@link DynamicPropertySource}.</li>
 * </ul>
 *
 * <p>Наследники получают ту же конфигурацию контекста, что и раньше давал профиль {@code test}
 * (см. {@code src/test/resources/application-test.yml}), но URL/логин/пароль БД динамически
 * подменяются на реальные значения контейнера.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
abstract class BasePostgresIntegrationTest {

    /** Образ PostgreSQL 16 + PostGIS 3.6 + pgRouting 4.0 (соответствует ТЗ: PostgreSQL 14+, PostGIS 3.x, pgRouting). */
    private static final String POSTGRES_IMAGE = "nickblah/pgrouting:16-postgis-3.6-pgrouting-4.0.1";

    static {
        // ВАЖНО: версия API задаётся ЧЕРЕЗ ENV (DOCKER_API_VERSION), а не System property.
        // docker-java читает конфигурацию из classpath:/docker-java.properties и env;
        // свойства вида -Ddocker-java.* он НЕ поддерживает (см. DefaultDockerClientConfig).
        checkDockerAvailability();
    }

    // ВАЖНО: без аннотации @Container! Контейнер запускается лениво (см. getPostgres())
    // и НЕ останавливается после каждого тестового класса.
    //
    // Причина регрессии (mvn clean test, падение HybridVisibilityGraphServiceTest с
    // "HikariPool-1 - Failed to validate connection ... Соединение уже было закрыто"):
    // при наличии @Container Testcontainers-JUnit 5 обрабатывает статическое поле как
    // per-class контейнер: перед КАЖДЫМ новым тестовым классом контейнер ОСТАНАВЛИВАЕТСЯ
    // и стартует заново. Spring же кеширует ApplicationContext между классами, а вместе
    // с ним — HikariCP-пул с соединениями к порту СТАРОГО контейнера. После перестановки
    // контейнера пул получает «мёртвые» соединения. При отдельном запуске одного класса
    // перестановки не происходит — поэтому соло-прогон проходил, а полный — нет.
    static final PostgreSQLContainer<?> POSTGRES = createContainer();

    /**
     * Создаёт контейнер PostgreSQL (PostGIS + pgRouting) без запуска.
     * Запуск выполняется лениво в {@link #getPostgres()}.
     */
    private static PostgreSQLContainer<?> createContainer() {
        return new PostgreSQLContainer<>(
                // Кастомный образ (не postgres:*) — явно заявляем Testcontainers,
                // что это совместимая замена postgres, иначе падает проверка
                // "Failed to verify that image ... is a compatible substitute for 'postgres'".
                DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("testdb")
                .withUsername("test")
                .withPassword("test")
                // Один контейнер на всю JVM: переиспользуется всеми классами-наследниками,
                // Flyway отрабатывает однократно при первом старте контекста Spring.
                .withReuse(true);
    }

    /**
     * Ленивый singleton: гарантирует, что контейнер запущен РОВНО ОДИН раз за JVM.
     *
     * <p>{@code start()} идемпотентен (GenericContainer проверяет состояние), поэтому
     * повторные вызовы из разных тестовых классов бесплатны. Контейнер переживает
     * завершение каждого класса и останавливается только Ryuk-потоном Testcontainers
     * после окончания всей тестовой JVM — ApplicationContext и HikariCP остаются
     * валидными для всех наследников.
     */
    static PostgreSQLContainer<?> getPostgres() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        return POSTGRES;
    }

    /**
     * Переопределяет свойства источника данных Spring реальными параметрами контейнера.
     *
     * @param registry реестр динамических свойств тестового контекста
     */
    @DynamicPropertySource
    static void overrideDatasourceProps(DynamicPropertyRegistry registry) {
        // ЛЕНИВЫЕ supplier-ы: контейнер стартует при ПЕРВОМ ОБРАЩЕНИИ к свойству
        // (в момент создания контекста), а не при регистрации.
        registry.add("spring.datasource.url", () -> getPostgres().getJdbcUrl());
        registry.add("spring.datasource.username", () -> getPostgres().getUsername());
        registry.add("spring.datasource.password", () -> getPostgres().getPassword());
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
    }

    /**
     * Проверяет доступность Docker и завершает инициализацию с понятной ошибкой, если его нет.
     *
     * <p>Печатает полную цепочку причин в stderr, т.к. Surefire обрезает стектрейсы
     * при падении статического инициализатора.
     */
    private static void checkDockerAvailability() {
        try {
            if (!DockerClientFactory.instance().isDockerAvailable()) {
                throw new IllegalStateException(
                        "Docker недоступен. Проверьте, что Docker Desktop запущен и доступен "
                                + "tcp://localhost:2375 (Settings -> General -> Expose daemon on "
                                + "tcp://localhost:2375 without TLS), либо задайте DOCKER_HOST. "
                                + "См. src/test/resources/testcontainers.properties.");
            }
        } catch (IllegalStateException e) {
            System.err.println("=== Testcontainers/Docker init FAILED ===");
            System.err.println(e.getMessage());
            System.err.println("=====================================");
            throw e;
        } catch (Throwable t) {
            System.err.println("=== Testcontainers/Docker init FAILED ===");
            Throwable cause = t;
            while (cause != null) {
                System.err.println(cause.getClass().getName() + ": " + cause.getMessage());
                StackTraceElement[] trace = cause.getStackTrace();
                for (StackTraceElement el : java.util.Arrays.copyOf(trace, Math.min(15, trace.length))) {
                    System.err.println("    at " + el);
                }
                cause = cause.getCause();
            }
            System.err.println("=====================================");
            throw new IllegalStateException(
                    "Не удалось подключиться к Docker: " + t
                            + ". Проверьте запуск Docker Desktop и версию Testcontainers (нужна >= 1.20 для Docker Engine 25+).",
                    t);
        }
    }
}
