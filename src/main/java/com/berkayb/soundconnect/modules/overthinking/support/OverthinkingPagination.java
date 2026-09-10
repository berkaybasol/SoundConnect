package com.berkayb.soundconnect.modules.overthinking.support;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.data.domain.*;

/** Public ordering never accepts relation fields, including the hidden post author. */
public final class OverthinkingPagination {
    public static final int MAX_SIZE = 50;
    private OverthinkingPagination() { }

    public static Pageable newest(Pageable requested) {
        return page(requested, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    public static Pageable page(Pageable requested, Sort order) {
        int page = requested == null || requested.isUnpaged() ? 0 : requested.getPageNumber();
        int size = requested == null || requested.isUnpaged() ? 20 : Math.min(requested.getPageSize(), MAX_SIZE);
        if (page < 0 || page > 1000 || size < 1) throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
        return PageRequest.of(page, size, order);
    }
}
