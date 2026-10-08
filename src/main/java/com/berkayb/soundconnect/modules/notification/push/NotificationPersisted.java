package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;

/** Synchronous, in-transaction hook. A failed durable delivery plan rolls back the inbox write. */
public record NotificationPersisted(Notification notification) { }
