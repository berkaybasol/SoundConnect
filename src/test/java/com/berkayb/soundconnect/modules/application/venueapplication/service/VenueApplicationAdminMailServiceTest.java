package com.berkayb.soundconnect.modules.application.venueapplication.service;

import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VenueApplicationAdminMailServiceTest {

	@Mock MailProducer mailProducer;
	@InjectMocks VenueApplicationAdminMailService service;

	@Test
	void keepsVenueFieldsAlignedWithTheirLabels() {
		ReflectionTestUtils.setField(service, "venueApplicationEmails", "ops@example.com");
		VenueApplication application = application();

		service.sendNewApplicationMail(application);

		ArgumentCaptor<MailSendRequest> captor = ArgumentCaptor.forClass(MailSendRequest.class);
		verify(mailProducer).send(captor.capture());
		MailSendRequest request = captor.getValue();
		assertThat(request.kind()).isEqualTo(MailKind.VENUE_APPLICATION_ADMIN);
		assertThat(request.subject()).isEqualTo("Yeni Mekan Başvurusu: Sahne İstanbul");
		assertThat(request.textBody())
				.contains("- Mekan Adı: Sahne İstanbul")
				.contains("- Adres: İstiklal Caddesi")
				.contains("- Telefon: 05550000000")
				.contains("- Şehir: İstanbul")
				.contains("- İlçe: Beyoğlu")
				.contains("- Mahalle: Asmalımescit");
		assertThat(request.params())
				.containsEntry("applicationType", "VENUE")
				.containsEntry("venueName", "Sahne İstanbul");
	}

	private VenueApplication application() {
		City city = City.builder().id(UUID.randomUUID()).name("İstanbul").build();
		District district = District.builder().id(UUID.randomUUID()).name("Beyoğlu").city(city).build();
		Neighborhood neighborhood = Neighborhood.builder()
				.id(UUID.randomUUID()).name("Asmalımescit").district(district).build();
		User applicant = User.builder()
				.id(UUID.randomUUID())
				.username("venue-owner")
				.email("owner@example.com")
				.build();
		return VenueApplication.builder()
				.id(UUID.randomUUID())
				.applicant(applicant)
				.venueName("Sahne İstanbul")
				.venueAddress("İstiklal Caddesi")
				.phone("05550000000")
				.city(city)
				.district(district)
				.neighborhood(neighborhood)
				.status(ApplicationStatus.PENDING)
				.applicationDate(LocalDateTime.of(2026, 7, 24, 1, 30))
				.build();
	}
}
