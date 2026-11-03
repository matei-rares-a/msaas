package com.saas.testsupport.ci;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checks that are about what the repository does not contain (P12.3): the deployment is one region, in the EU
 * (REQ-DATA-13), and nothing the requirements put out of scope has been introduced: read replicas, autoscaling,
 * cross-tenant monitoring, the TEMP role, broadcast announcements, several currencies, several regions, sick leave,
 * half days and carrying leave over. They are plain text searches over the files that describe the deployment and over
 * the production sources; comment lines are skipped, because a comment may well say what the code does not do.
 */
class DeploymentScopeTest {

    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final List<String> SKIPPED = List.of("node_modules", "target", ".git", ".agents", "dist", ".angular", "test-results");

    private static final Pattern DEPLOYMENT_FILE = Pattern.compile(
            "(^|.*/)(Dockerfile|docker-compose[^/]*\\.ya?ml|ci\\.ya?ml|.*\\.sh|.*\\.ini|application[^/]*\\.(ya?ml|properties))$");
    private static final Pattern PRODUCTION_SOURCE = Pattern.compile(
            "(^|.*/)src/main/.*\\.(java|sql|ya?ml|properties)$|^angular_base/src/.*\\.(ts|html)$");
    private static final Pattern COMMENT = Pattern.compile("^\\s*(//|/\\*|\\*|--|#|<!--)");

    /** Repository-relative paths (forward slashes) of the files that match, without build output, dependencies and tests. */
    private static List<String> files(Pattern wanted) throws IOException {
        List<String> found = new ArrayList<>();
        Files.walkFileTree(REPO_ROOT, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                String name = String.valueOf(directory.getFileName());
                boolean skipped = !directory.equals(REPO_ROOT) && (SKIPPED.contains(name) || name.equals("test") && directory.getParent().endsWith("src")
                        || directory.equals(REPO_ROOT.resolve("angular_base/e2e")));
                return skipped ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                String path = REPO_ROOT.relativize(file).toString().replace('\\', '/');
                if (!path.endsWith(".spec.ts") && wanted.matcher(path).matches()) {
                    found.add(path);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }

    /** "file:line text" for every line outside comments that matches. */
    private static List<String> hits(List<String> files, Pattern pattern) throws IOException {
        List<String> found = new ArrayList<>();
        for (String file : files) {
            List<String> lines = Files.readAllLines(REPO_ROOT.resolve(file));
            for (int i = 0; i < lines.size(); i++) {
                if (!COMMENT.matcher(lines.get(i)).find() && pattern.matcher(lines.get(i)).find()) {
                    found.add(file + ":" + (i + 1) + " " + lines.get(i).trim());
                }
            }
        }
        return found;
    }

    @Test
    void thereAreFilesToCheck() throws IOException {
        assertThat(files(DEPLOYMENT_FILE)).contains("docker-compose.yml", "api-gateway/Dockerfile", ".github/workflows/ci.yml", "db/init/01-topology.sh");
        assertThat(files(PRODUCTION_SOURCE)).hasSizeGreaterThan(100);
    }

    @Test
    @Tag("REQ-DATA-13")
    void theDeploymentNamesNoCloudRegionOutsideTheEu() throws IOException {
        Pattern outsideTheEu = Pattern.compile("(?i)\\b(us|ap|sa|ca|me|af|cn)-(east|west|north|south|central|southeast|northeast|northwest|southwest)-\\d\\b"
                + "|\\b(east|west|southcentral|northcentral|west central)us\\d?\\b|\\b(asia|australia|northamerica|southamerica|us)-[a-z]+\\d\\b"
                + "|\\b(japan|korea|india|uae|brazil|canada|southafrica|australia)(east|west|central|north|south)\\b");

        assertThat(hits(files(DEPLOYMENT_FILE), outsideTheEu)).isEmpty();
    }

    @Test
    @Tag("REQ-DATA-13")
    void anyRegionSettingOfTheDeploymentIsAnEuRegion() throws IOException {
        Pattern regionSetting = Pattern.compile("(?i)\\b[a-z_.-]*region[a-z_.-]*\\s*[:=]\\s*\\S+");
        Pattern euValue = Pattern.compile("(?i)[:=]\\s*[\"']?(eu-[a-z]+-\\d|europe[-a-z0-9]*|(north|west)europe|francecentral|germanywestcentral|swedencentral|\\$\\{[^}]*\\})");

        List<String> settings = hits(files(DEPLOYMENT_FILE), regionSetting);

        assertThat(settings).allSatisfy(setting -> assertThat(euValue.matcher(setting).find()).as(setting).isTrue());
    }

    @Test
    @Tag("REQ-DATA-13")
    void theDataLivesInOneDatabaseServerWithTheGlobalDatabaseAndOneShard() throws IOException {
        String compose = Files.readString(REPO_ROOT.resolve("docker-compose.yml"));
        String topology = Files.readString(REPO_ROOT.resolve("db/init/01-topology.sh"));

        assertThat(Pattern.compile("(?m)^\\s+image:\\s*postgres:").matcher(compose).results().count()).as("one PostgreSQL server").isEqualTo(1);
        assertThat(Pattern.compile("(?m)^CREATE DATABASE (\\w+);").matcher(topology).results().map(match -> match.group(1)).toList())
                .as("the global database and a single shard").containsExactly("saas_global", "saas_shard_1");
    }

    @Test
    void thereAreNoReadReplicasNoAutoscalingAndNoMonitoringAcrossTenants() throws IOException {
        Pattern pattern = Pattern.compile("(?i)read[-_ ]?replica|hot_standby|primary_conninfo|standby[-_]?mode|\\breplicas\\s*:|autoscal|HorizontalPodAutoscaler"
                + "|\\bscale\\s*:|prometheus|grafana|cross-tenant[-_ ]metrics|platform[-_ ]metrics");

        assertThat(hits(files(DEPLOYMENT_FILE), pattern)).isEmpty();
    }

    @Test
    void thereIsNoRoleOtherThanAdminManagerAndUserAndNoTempRole() throws IOException {
        assertThat(Files.readString(REPO_ROOT.resolve("organization-service/src/main/java/com/saas/org/entity/Membership.java")))
                .contains("enum Role {ADMIN, MANAGER, USER}");
        assertThat(hits(files(PRODUCTION_SOURCE), Pattern.compile("\\bTEMP\\b|\"TEMP\"|'TEMP'"))).isEmpty();
    }

    @Test
    void thereAreNoBroadcastAnnouncementsAndOnlyOneCurrency() throws IOException {
        assertThat(hits(files(PRODUCTION_SOURCE), Pattern.compile("(?i)broadcast|announcement"))).isEmpty();
        assertThat(hits(files(PRODUCTION_SOURCE), Pattern.compile("(?i)\\bcurrency\\b|\\b(USD|GBP|RON|CHF|JPY)\\b|multi[-_]?currency"))).isEmpty();
    }

    @Test
    void leaveHasNoSickLeaveNoHalfDaysAndNoCarryOver() throws IOException {
        assertThat(Files.readString(REPO_ROOT.resolve("organization-service/src/main/java/com/saas/org/leave/LeaveType.java")))
                .contains("ODIHNA, FARA_PLATA").doesNotContain("MEDICAL");
        assertThat(hits(files(PRODUCTION_SOURCE), Pattern.compile("(?i)sick|medical|half[-_ ]?day|carry[-_ ]?over|report[-_ ]?zile|jumatate")))
                .isEmpty();
    }
}
