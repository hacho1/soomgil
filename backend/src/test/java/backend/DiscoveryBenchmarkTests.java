package backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SOOMGIL_BENCHMARK_ARCHIVE",matches=".+")
class DiscoveryBenchmarkTests {
    @TempDir Path root;
    static class TracedJdbc extends JdbcTemplate {
        String sql;
        Object[] args;
        TracedJdbc(DriverManagerDataSource source) { super(source); }
        @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
            if (sql.contains("COUNT(*) OVER()")) { this.sql=sql; this.args=args; }
            return super.queryForList(sql,args);
        }
    }

    @Test void benchmarkOnlyAnIsolatedCopyOfAVerifiedBackup() throws Exception {
        Path archive=Path.of(System.getenv("SOOMGIL_BENCHMARK_ARCHIVE"));
        var mapper=new ObjectMapper();
        var metadata=mapper.readTree(Path.of(archive+".json").toFile());
        assertThat(metadata.path("verified").asBoolean()).isTrue();
        assertThat(BackupArchive.sha256(archive)).isEqualToIgnoringCase(metadata.path("sha256").asText());
        Path database;
        try (var zip=new ZipFile(archive.toFile())) {
            var entries=Collections.list(zip.entries());
            assertThat(entries).hasSize(1);
            String name=entries.get(0).getName();
            assertThat(name).matches("[A-Za-z0-9_-]+\\.mv\\.db");
            database=root.resolve(name);
            try (var input=zip.getInputStream(entries.get(0))) { Files.copy(input,database); }
        }
        String base=database.toString().substring(0,database.toString().length()-6).replace('\\','/');
        var source=new DriverManagerDataSource("jdbc:h2:file:"+base+";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;IFEXISTS=TRUE","sa","");
        try (var keeper=source.getConnection()) {
            var jdbc=new TracedJdbc(source);
            var queries=new PlaceQueries(jdbc,new SearchCatalog(mapper));
            long count=jdbc.queryForObject("SELECT COUNT(*) FROM places",Long.class);
            long indexing=System.nanoTime(); queries.initialize(); queries.backfill();
            System.out.println("Real backup places="+count+", one-time indexing ms="+(System.nanoTime()-indexing)/1_000_000);
            var profile=DiscoveryRecommendations.Profile.guest();
            for (int i=0;i<3;i++) {
                long start=System.nanoTime(); var rows=queries.discoveryCandidates(profile,LocalDate.of(2026,9,15));
                System.out.println("Real backup uncached candidate query ms="+(System.nanoTime()-start)/1_000_000+", candidates="+rows.size());
                assertThat(rows.size()).isBetween(1,360);
            }
            for (String query : List.of("바다","카페","강릉 바다","산책","캠핑","바다 카페")) {
                long start=System.nanoTime(); var page=queries.search(query,"","","recommended",1,6,profile);
                System.out.println("Real backup search="+query+", ms="+(System.nanoTime()-start)/1_000_000+", total="+page.total()+", names="+page.rows().stream().map(p->p.get("name")).toList());
                assertThat(jdbc.queryForObject("EXPLAIN "+jdbc.sql,String.class,jdbc.args)).doesNotContain("places_discovery_idx");
                String previousSql=jdbc.sql.replace("SELECT p.id,COUNT(*) OVER() AS result_total FROM", "SELECT p.* FROM");
                String previousCount="SELECT COUNT(*) FROM places p WHERE "+previousSql.split(" WHERE ",2)[1].split(" ORDER BY ",2)[0];
                Object[] previousArgs=jdbc.args;
                Object[] filters=Arrays.copyOf(previousArgs,(int)previousCount.chars().filter(c->c=='?').count());
                long previousStart=System.nanoTime();
                long previousTotal=jdbc.queryForObject(previousCount,Long.class,filters);
                var previousRows=jdbc.queryForList(previousSql,previousArgs);
                System.out.println("Previous two-scan search="+query+", ms="+(System.nanoTime()-previousStart)/1_000_000);
                assertThat(page.total()).isEqualTo(previousTotal);
                assertThat(page.rows()).isEqualTo(previousRows);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM places",Long.class)).isEqualTo(count);
        }
    }
}
