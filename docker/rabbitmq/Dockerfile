FROM rabbitmq:3.13.7-management

ARG DELAYED_MESSAGE_PLUGIN_VERSION=3.13.0
ARG DELAYED_MESSAGE_PLUGIN_SHA256=3479180df03fa830fd59ccf638a60eee8f0e8261524a8616685522d7b8d9a9ee

ADD https://github.com/rabbitmq/rabbitmq-delayed-message-exchange/releases/download/v${DELAYED_MESSAGE_PLUGIN_VERSION}/rabbitmq_delayed_message_exchange-${DELAYED_MESSAGE_PLUGIN_VERSION}.ez /opt/rabbitmq/plugins/rabbitmq_delayed_message_exchange-${DELAYED_MESSAGE_PLUGIN_VERSION}.ez

RUN chmod 0644 /opt/rabbitmq/plugins/rabbitmq_delayed_message_exchange-${DELAYED_MESSAGE_PLUGIN_VERSION}.ez \
    && echo "${DELAYED_MESSAGE_PLUGIN_SHA256}  /opt/rabbitmq/plugins/rabbitmq_delayed_message_exchange-${DELAYED_MESSAGE_PLUGIN_VERSION}.ez" | sha256sum --check --strict \
    && rabbitmq-plugins enable --offline rabbitmq_delayed_message_exchange
