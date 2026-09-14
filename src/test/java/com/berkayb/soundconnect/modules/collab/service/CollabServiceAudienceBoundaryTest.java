package com.berkayb.soundconnect.modules.collab.service;

import com.berkayb.soundconnect.modules.collab.support.CollabAccessGuard;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollabServiceAudienceBoundaryTest {
    static Stream<Arguments> operations() {
        var operations = Stream.of(CollabService.class, CollabActorService.class, CollabReportModerationService.class)
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> Modifier.isPublic(method.getModifiers()) && method.getParameterCount() > 0
                        && method.getParameterTypes()[0] == UUID.class).toList();
        // 23 user actions, 2 actor entry points, 2 moderation entry points.
        assertThat(operations).hasSize(27);
        return operations.stream().flatMap(method -> Stream.of("LISTENER", "MIXED_ROLES", "RETAINED_PROFILE")
                .map(identity -> Arguments.of(method, identity)));
    }

    @ParameterizedTest(name = "{0}: {1}") @MethodSource("operations")
    void listenerCannotReachRepositoriesIdempotentHistoryOrMutationThroughServiceCalls(Method method, String identity) throws Exception {
        UUID viewer = UUID.randomUUID();
        var users = mock(UserRepository.class);
        when(users.findRoleNamesByUserId(viewer)).thenReturn(switch (identity) {
            case "MIXED_ROLES" -> Set.of("ROLE_LISTENER", "ROLE_MUSICIAN", "ROLE_ADMIN");
            case "RETAINED_PROFILE" -> Set.of("ROLE_MUSICIAN", "ROLE_ADMIN");
            default -> Set.of("ROLE_LISTENER");
        });
        when(users.findExistingPersonalProfileRoleNames(viewer)).thenReturn(Set.of("ROLE_LISTENER"));
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var guard = new CollabAccessGuard(users, jdbc);
        var untouched = new ArrayList<Object>();
        var constructor = method.getDeclaringClass().getConstructors()[0];
        Object[] dependencies = Arrays.stream(constructor.getParameterTypes()).map(type -> {
            if (type == CollabAccessGuard.class) return guard;
            Object dependency = mock(type); untouched.add(dependency); return dependency;
        }).toArray();
        Object service = constructor.newInstance(dependencies);
        Object[] arguments = Arrays.stream(method.getParameterTypes()).map(type ->
                type == UUID.class ? UUID.randomUUID() : type == int.class ? 0 : null).toArray();
        arguments[0] = viewer;

        var failure = catchThrowableOfType(() -> method.invoke(service, arguments), InvocationTargetException.class);
        assertThat(failure.getCause()).isInstanceOfSatisfying(SoundConnectException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.COLLAB_FORBIDDEN));
        verifyNoInteractions(untouched.toArray());
        verifyNoInteractions(jdbc);
    }
}
