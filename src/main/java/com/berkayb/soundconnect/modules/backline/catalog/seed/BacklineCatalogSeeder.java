package com.berkayb.soundconnect.modules.backline.catalog.seed;

import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategory;
import com.berkayb.soundconnect.modules.backline.catalog.repository.BacklineCategoryRepository;
import com.berkayb.soundconnect.modules.backline.catalog.support.BacklineCategoryNames;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
@ConditionalOnProperty(
        value = "app.backline.catalog.seed.enabled",
        havingValue = "true",
        matchIfMissing = false
)
@RequiredArgsConstructor
@Slf4j
public class BacklineCatalogSeeder implements ApplicationRunner {
    private static final String SEED_RESOURCE = "backline-category-seed.json";

    private final BacklineCategoryRepository categoryRepository;
    private final ObjectMapper objectMapper;
    private final BacklineCategoryNames categoryNames;

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        List<RootSeed> roots = readSeed();
        validateSeed(roots);

        int createdRoots = 0;
        int createdChildren = 0;
        for (int rootIndex = 0; rootIndex < roots.size(); rootIndex++) {
            RootSeed rootSeed = roots.get(rootIndex);
            String rootName = categoryNames.displayName(rootSeed.name());
            String normalizedRootName = categoryNames.normalizedName(rootName);
            BacklineCategory root = categoryRepository.findByCode(rootSeed.code())
                    .or(() -> categoryRepository.findByParentIsNullAndNormalizedName(normalizedRootName))
                    .orElse(null);
            if (root == null) {
                root = categoryRepository.save(BacklineCategory.createRoot(
                        rootSeed.code(),
                        rootName,
                        normalizedRootName,
                        cleanOptional(rootSeed.iconKey()),
                        rootIndex
                ));
                createdRoots++;
            } else if (!root.isRoot()) {
                throw new IllegalStateException("Backline seed root code belongs to a child: " + rootSeed.code());
            } else {
                root.synchronizeReferenceData(
                        null,
                        rootName,
                        normalizedRootName,
                        cleanOptional(rootSeed.iconKey()),
                        rootIndex
                );
                root = categoryRepository.save(root);
            }
            BacklineCategory persistedRoot = root;

            for (int childIndex = 0; childIndex < rootSeed.children().size(); childIndex++) {
                ChildSeed childSeed = rootSeed.children().get(childIndex);
                String childName = categoryNames.displayName(childSeed.name());
                String normalizedChildName = categoryNames.normalizedName(childName);
                BacklineCategory child = categoryRepository.findByCode(childSeed.code())
                        .or(() -> categoryRepository.findByParentIdAndNormalizedName(
                                persistedRoot.getId(),
                                normalizedChildName
                        ))
                        .orElse(null);
                if (child == null) {
                    categoryRepository.save(BacklineCategory.createChild(
                            persistedRoot,
                            childSeed.code(),
                            childName,
                            normalizedChildName,
                            childIndex
                    ));
                    createdChildren++;
                } else {
                    child.synchronizeReferenceData(
                            persistedRoot,
                            childName,
                            normalizedChildName,
                            null,
                            childIndex
                    );
                    categoryRepository.save(child);
                }
            }
        }

        log.info(
                "[backline-catalog-seed] synchronized rootsCreated={} childrenCreated={}",
                createdRoots,
                createdChildren
        );
    }

    private List<RootSeed> readSeed() throws Exception {
        try (InputStream input = new ClassPathResource(SEED_RESOURCE).getInputStream()) {
            return objectMapper.readValue(input, new TypeReference<>() {});
        }
    }

    private void validateSeed(List<RootSeed> roots) {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalStateException("Backline category seed cannot be empty");
        }
        Set<String> codes = new HashSet<>();
        for (RootSeed root : roots) {
            validateCode(root.code(), codes);
            if (!StringUtils.hasText(root.name()) || root.children() == null || root.children().isEmpty()) {
                throw new IllegalStateException("Every backline root seed must have a name and children");
            }
            for (ChildSeed child : root.children()) {
                validateCode(child.code(), codes);
                if (!StringUtils.hasText(child.name())) {
                    throw new IllegalStateException("Every backline child seed must have a name");
                }
            }
        }
    }

    private void validateCode(String code, Set<String> codes) {
        if (!StringUtils.hasText(code) || !code.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalStateException("Invalid backline seed code: " + code);
        }
        if (!codes.add(code)) {
            throw new IllegalStateException("Duplicate backline seed code: " + code);
        }
    }

    private String cleanOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record RootSeed(String code, String name, String iconKey, List<ChildSeed> children) {}

    private record ChildSeed(String code, String name) {}
}
