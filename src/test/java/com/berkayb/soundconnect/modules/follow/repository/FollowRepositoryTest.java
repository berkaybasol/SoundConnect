package com.berkayb.soundconnect.modules.follow.repository;

import com.berkayb.soundconnect.modules.follow.entity.Follow;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
// Her test sınıfına benzersiz H2 ver → çarpışma yok
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sc-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("repo")
class FollowRepositoryTest {
	
	@Autowired
	FollowRepository followRepository;
	@Autowired UserRepository userRepository;
	@Autowired CityRepository cityRepository;
	@Autowired DistrictRepository districtRepository;
	@Autowired NeighborhoodRepository neighborhoodRepository;
	
	City city;
	District district;
	Neighborhood neighborhood;
	User u1;
	User u2;
	User u3;
	
	@BeforeEach
	void setup() {
		// child -> parent sırayla temizle
		followRepository.deleteAll();
		userRepository.deleteAll();
		neighborhoodRepository.deleteAll();
		districtRepository.deleteAll();
		cityRepository.deleteAll();
		
		city = cityRepository.save(City.builder().name("C_" + UUID.randomUUID()).build());
		district = districtRepository.save(District.builder().name("D_" + UUID.randomUUID()).city(city).build());
		neighborhood = neighborhoodRepository.save(Neighborhood.builder().name("N_" + UUID.randomUUID()).district(district).build());
		
		u1 = userRepository.save(User.builder()
		                             .username("u1_" + UUID.randomUUID())
		                             .password("pwd")
		                             .provider(AuthProvider.LOCAL)
		                             .emailVerified(true)
		                             .city(city)
		                             .build());
		
		u2 = userRepository.save(User.builder()
		                             .username("u2_" + UUID.randomUUID())
		                             .password("pwd")
		                             .provider(AuthProvider.LOCAL)
		                             .emailVerified(true)
		                             .city(city)
		                             .build());
		
		u3 = userRepository.save(User.builder()
		                             .username("u3_" + UUID.randomUUID())
		                             .password("pwd")
		                             .provider(AuthProvider.LOCAL)
		                             .emailVerified(true)
		                             .city(city)
		                             .build());
	}
	
	@Test
	void existsByFollowerAndFollowing_should_work() {
		assertFalse(followRepository.existsByFollowerAndFollowing(u1, u2));
		
		followRepository.saveAndFlush(Follow.builder()
		                                    .follower(u1)
		                                    .following(u2)
		                                    .followedAt(LocalDateTime.now())
		                                    .build());
		
		assertTrue(followRepository.existsByFollowerAndFollowing(u1, u2));
	}
	
	@Test
	void findAllByFollower_should_return_list() {
		followRepository.saveAndFlush(Follow.builder().follower(u1).following(u2).followedAt(LocalDateTime.now()).build());
		followRepository.saveAndFlush(Follow.builder().follower(u1).following(u3).followedAt(LocalDateTime.now()).build());
		
		List<Follow> list = followRepository.findAllByFollower(u1);
		assertEquals(2, list.size());
		var targets = list.stream().map(f -> f.getFollowing().getId()).toList();
		assertTrue(targets.contains(u2.getId()));
		assertTrue(targets.contains(u3.getId()));
	}
	
	@Test
	void findAllByFollowing_should_return_list() {
		followRepository.saveAndFlush(Follow.builder().follower(u1).following(u2).followedAt(LocalDateTime.now()).build());
		followRepository.saveAndFlush(Follow.builder().follower(u3).following(u2).followedAt(LocalDateTime.now()).build());
		
		List<Follow> list = followRepository.findAllByFollowing(u2);
		assertEquals(2, list.size());
		var sources = list.stream().map(f -> f.getFollower().getId()).toList();
		assertTrue(sources.contains(u1.getId()));
		assertTrue(sources.contains(u3.getId()));
	}
	
	@Test
	void findByFollowerAndFollowing_should_find() {
		followRepository.saveAndFlush(Follow.builder().follower(u1).following(u2).followedAt(LocalDateTime.now()).build());
		
		var opt = followRepository.findByFollowerAndFollowing(u1, u2);
		assertTrue(opt.isPresent());
		assertEquals(u1.getId(), opt.get().getFollower().getId());
		assertEquals(u2.getId(), opt.get().getFollowing().getId());
	}
	
	@Test
	void count_queries_should_work() {
		followRepository.saveAndFlush(Follow.builder().follower(u1).following(u2).followedAt(LocalDateTime.now()).build());
		followRepository.saveAndFlush(Follow.builder().follower(u1).following(u3).followedAt(LocalDateTime.now()).build());
		followRepository.saveAndFlush(Follow.builder().follower(u3).following(u2).followedAt(LocalDateTime.now()).build());
		
		assertEquals(2L, followRepository.countByFollower(u1));
		assertEquals(2L, followRepository.countByFollowing(u2));
	}
	
	@Test
	void unique_constraint_should_prevent_duplicate_follow() {
		followRepository.saveAndFlush(Follow.builder()
		                                    .follower(u1)
		                                    .following(u2)
		                                    .followedAt(LocalDateTime.now())
		                                    .build());
		
		assertThrows(DataIntegrityViolationException.class, () ->
				followRepository.saveAndFlush(Follow.builder()
				                                    .follower(u1)
				                                    .following(u2)
				                                    .followedAt(LocalDateTime.now())
				                                    .build())
		);
	}
}