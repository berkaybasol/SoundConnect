package com.berkayb.soundconnect.shared.mail.consumer;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DlqMailJobConsumer {
	
	private final MailJobHelper helper;
	
	/**
	 * DLQ kuyruğunu dinler her mesajda alarm/log mekanizması tetiklenir.
	 * İleride buraya Slack/Sentry/Prometheus entegrasyonu takacağız.
	 */
	@RabbitListener(queues = "${mail.dlq}", containerFactory = "mailListenerFactory")
	public void listenMailDlq(Object payload) {
		if (payload instanceof MailSendRequest req) {
			log.error("Mail DLQ — kind={}, to={}, paramsCount={}",
			          req.kind(),
			          helper.maskEmail(req.to()),
			          req.params() == null ? 0 : req.params().size());
			return;
		}
		
		if (payload instanceof Map<?, ?> map) {
			Object toObj = map.get("to");
			Object kind  = map.get("kind");
			String toMasked = helper.maskEmail(String.valueOf(toObj != null ? toObj : "<unknown>"));
			log.error("Mail DLQ (map) — kind={}, to={}, fieldCount={}",
			          safeKind(kind),
			          toMasked,
			          map.size());
			return;
		}
		
		log.error("Mail DLQ (raw) — type={}, value=<redacted>",
		          payload == null ? "null" : payload.getClass().getName());
		// TODO: buraya Slack/Sentry entegrasyonu gelecek
	}
	
	private String safeKind(Object kind) {
		if (!(kind instanceof String value) || !value.matches("[A-Za-z_]{1,32}")) {
			return "<unknown>";
		}
		return value;
	}
}
