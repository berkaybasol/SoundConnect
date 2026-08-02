package com.berkayb.soundconnect.modules.backline.catalog.seed;

import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategory;
import com.berkayb.soundconnect.modules.backline.catalog.repository.BacklineCategoryRepository;
import com.berkayb.soundconnect.modules.backline.catalog.support.BacklineCategoryNames;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sc-backline-seed-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("repo")
class BacklineCatalogSeederTest {

    @Autowired
    private BacklineCategoryRepository categoryRepository;

    @Test
    void seedsTheProductionCatalogIdempotently() throws Exception {
        BacklineCategory legacyAmplifierRoot = categoryRepository.save(
                BacklineCategory.createRoot(
                        "guitar-amplifiers",
                        "Eski Gitar Amfileri",
                        "eski gitar amfileri",
                        "legacy-icon",
                        99
                )
        );
        categoryRepository.save(BacklineCategory.createRoot(
                "guitars-basses",
                "Eski Gitarlar",
                "eski gitarlar",
                "legacy-icon",
                98
        ));
        categoryRepository.save(BacklineCategory.createChild(
                legacyAmplifierRoot,
                "guitar-modelers-multi-effects",
                "Eski Modelleyiciler",
                "eski modelleyiciler",
                99
        ));
        BacklineCatalogSeeder seeder = new BacklineCatalogSeeder(
                categoryRepository,
                new ObjectMapper(),
                new BacklineCategoryNames()
        );
        DefaultApplicationArguments arguments = new DefaultApplicationArguments(new String[0]);

        seeder.run(arguments);

        List<BacklineCategory> roots = categoryRepository
                .findByParentIsNullAndActiveTrue(PageRequest.of(0, 20))
                .getContent();
        assertThat(roots).hasSize(10);
        assertThat(categoryRepository.findActiveChildren(
                roots.stream().map(BacklineCategory::getId).toList()
        )).hasSize(79);
        assertThat(categoryRepository.findByCode("dynamic-microphones"))
                .get()
                .extracting(BacklineCategory::getName)
                .isEqualTo("Dinamik Mikrofonlar");
        assertThat(categoryRepository.findByCode("guitar-modelers-multi-effects"))
                .get()
                .satisfies(category -> {
                    assertThat(category.getName()).isEqualTo("Modelleyiciler & Multi-Efektler");
                    assertThat(category.getParent().getCode()).isEqualTo("guitars-basses");
                });

        seeder.run(arguments);

        assertThat(categoryRepository.count()).isEqualTo(89);
    }
}
