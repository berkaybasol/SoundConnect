package com.berkayb.soundconnect.modules.application.studioapplication.service;

import com.berkayb.soundconnect.modules.application.studioapplication.entity.StudioApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StudioApplicationAdminMailServiceTest {

	@Mock MailProducer mailProducer;
	@InjectMocks StudioApplicationAdminMailService service;

	@Test
	void sendsDistinctStudioSpecificNotificationsWithCorrectApplicationData() {
		ReflectionTestUtils.setField(
				service,
				"studioApplicationEmails",
				"ops@example.com, ops@example.com, admin@example.com"
		);
		StudioApplication application = application();

		service.sendNewApplicationMail(application);

		ArgumentCaptor<MailSendRequest> captor = ArgumentCaptor.forClass(MailSendRequest.class);
		verify(mailProducer, times(2)).send(captor.capture());
		List<MailSendRequest> requests = captor.getAllValues();
		assertThat(requests).extracting(MailSendRequest::to)
				.containsExactly("ops@example.com", "admin@example.com");
		MailSendRequest request = requests.getFirst();
		assertThat(request.kind()).isEqualTo(MailKind.STUDIO_APPLICATION_ADMIN);
		assertThat(request.subject()).isEqualTo("Yeni Stüdyo Başvurusu: Devo Studio");
		assertThat(request.textBody())
				.contains("Yeni bir stüdyo başvurusu alındı.")
				.contains("- Stüdyo Adı: Devo Studio")
				.contains("- Adres: Moda Caddesi")
				.contains("- Telefon: 05551234567")
				.contains("- Şehir: İstanbul")
				.contains("- İlçe: Kadıköy")
				.contains("- Mahalle: Moda")
				.doesNotContain("Yeni bir mekan başvurusu");
		assertThat(request.params())
				.containsEntry("applicationType", "STUDIO")
				.containsEntry("studioName", "Devo Studio");
	}

	private StudioApplication application() {
		City city = City.builder().id(UUID.randomUUID()).name("İstanbul").build();
		District district = District.builder().id(UUID.randomUUID()).name("Kadıköy").city(city).build();
		Neighborhood neighborhood = Neighborhood.builder()
				.id(UUID.randomUUID()).name("Moda").district(district).build();
		User applicant = User.builder()
				.id(UUID.randomUUID())
				.username("studio-owner")
				.email("owner@example.com")
				.build();
		return StudioApplication.builder()
				.id(UUID.randomUUID())
				.applicant(applicant)
				.studioName("Devo Studio")
				.studioAddress("Moda Caddesi")
				.phone("05551234567")
				.city(city)
				.district(district)
				.neighborhood(neighborhood)
				.status(ApplicationStatus.PENDING)
				.applicationDate(LocalDateTime.of(2026, 7, 24, 1, 30))
				.build();
	}
}
