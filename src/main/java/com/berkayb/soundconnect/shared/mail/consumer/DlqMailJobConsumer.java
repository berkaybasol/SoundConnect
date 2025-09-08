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
			log.error("Mail DLQ — kind={}, to={}, subject={}, paramsKeys={}",
			          req.kind(),
			          helper.maskEmail(req.to()),
			          req.subject(),
			          req.params() == null ? "[]" : req.params().keySet());
			return;
		}
		
		if (payload instanceof Map<?, ?> map) {
			Object toObj = map.get("to");
			Object kind  = map.get("kind");
			Object subj  = map.get("subject");
			
			String toMasked = helper.maskEmail(String.valueOf(toObj != null ? toObj : "<unknown>"));
			log.error("Mail DLQ (map) — kind={}, to={}, subject={}, keys={}",
			          kind != null ? kind : "<unknown>",
			          toMasked,
			          subj != null ? subj : "<unknown>",
			          map.keySet());
			return;
		}
		
		log.error("Mail DLQ (raw) — type={}, value={}",
		          payload == null ? "null" : payload.getClass().getName(), payload);
		// TODO: buraya Slack/Sentry entegrasyonu gelecek
	}
	
	private Object safeParamKeys(Map<String, Object> params) {
		return (params == null) ? "[]" : params.keySet();
	}
}