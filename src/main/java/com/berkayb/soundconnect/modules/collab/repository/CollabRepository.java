package com.berkayb.soundconnect.modules.collab.repository;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
import org.hibernate.type.descriptor.converter.spi.JpaAttributeConverter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface CollabRepository extends JpaRepository<Collab, UUID>,
                                          JpaSpecificationExecutor<Collab> {
	
	// belirli bir kullanicinin sahibi oldugu ilanlardan id'si verilen collab'i getirir.
	Optional<Collab> findByIdAndOwner_Id(UUID collabId, UUID ownerId);
	
	/**
	 * daily bir ilana hizlica erismek icin kullanilan method
	 * expirationTime hesaplamasi, Redis TTL setup ve frontend tarafinda daily mi normal mi kontrolunu hedefliyoruz
 	 */
	Optional<Collab> findByIdAndDailyTrue(UUID collabId);
}