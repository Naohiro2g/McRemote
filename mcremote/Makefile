# Dont'use gradlew without this Makefile,
# or you will fail to build the plugin.
# Becasue -PmcVersion and -PpluginVersion are required to build.

MC_DIR := $${HOME}/Documents/MINECRAFT_SERVERS/PaperMC
MC_VERSION := 1.21.4
PLUGIN_VERSION := 0.6.11

update:
	./gradlew clean build -PmcVersion=$(MC_VERSION) -PpluginVersion=$(PLUGIN_VERSION) && \
	rm -fr  $(MC_DIR)/plugins/McRemote && \
	rm -f $(MC_DIR)/plugins/mc-remote-*.jar && \
	cp build/libs/mc-remote-$(MC_VERSION)-$(PLUGIN_VERSION).jar $(MC_DIR)/plugins/

run:
	cd $(MC_DIR) && \
	screen -dmS minecraft java -Xmx8G -Xms8G -jar paper.jar

stop:
	cd $(MC_DIR) && \
	screen -S minecraft -X stuff "stop\015"

restart:
	-make stop
	sleep 5
	make run

update-restart:
	make update
	make restart
