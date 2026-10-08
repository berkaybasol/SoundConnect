package com.berkayb.soundconnect.modules.notification.campaign;

import com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard;
import com.berkayb.soundconnect.modules.event.service.EventService;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.mapper.MediaAssetMapper;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import org.springframework.transaction.PlatformTransactionManager;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static com.berkayb.soundconnect.modules.notification.campaign.CampaignContract.*;

class CampaignTargetsTest {
    @Test void ownerWithoutPersonalProfileCanConfigurePublicModuleTargets() {
        var access=mock(CampaignAccess.class);var actor=UUID.randomUUID();
        var targets=new CampaignTargets(mock(CampaignStore.class),access,mock(CommentTargetAccessGuard.class),mock(EventService.class),
            mock(MediaAssetRepository.class),mock(MediaAssetMapper.class),mock(PublicProfileResolverService.class),mock(PlatformTransactionManager.class));
        when(access.profile(actor)).thenReturn(null);
        for(Kind kind:new Kind[]{Kind.HOME,Kind.EVENTS,Kind.TABLES,Kind.COLLAB,Kind.MARKETPLACE})
            assertThatCode(()->targets.validate(actor,new Target(kind,null))).doesNotThrowAnyException();
        verify(access,times(5)).requireActive(actor);
    }
}
