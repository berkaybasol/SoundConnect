package com.berkayb.soundconnect.modules.search.service;

import com.berkayb.soundconnect.modules.profile.shared.visibility.ProfileBlockVisibility;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public final class VisibleProfileSearch {
    private VisibleProfileSearch() { }

    /** A hidden match must not displace a visible match just outside the repository's first page. */
    public static <T> List<T> collect(int limit, Function<PageRequest, List<T>> query,
                                      Function<T, UUID> owner, ProfileBlockVisibility visibility) {
        var page = PageRequest.of(0, limit);
        var visible = new ArrayList<T>(limit);
        while (visible.size() < limit) {
            var rows = query.apply(page);
            var hidden = visibility.hiddenOwners(rows.stream().map(owner).filter(Objects::nonNull).toList());
            for (T row : rows) {
                UUID id = owner.apply(row);
                if (id == null || !hidden.contains(id)) visible.add(row);
                if (visible.size() == limit) break;
            }
            if (rows.size() < limit) break;
            page = page.next();
        }
        return visible;
    }
}
