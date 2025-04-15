# Description: Makefile for Minecraft server development and deployment
# Author: Naohiro Tsuji
# NOT-IN-USE 現在は使っていない。すべてGradleに集約済み

# Minecraft server version and plugin version
MC_VERSION := 1.21.4
PLUGIN_VERSION := 0.6.18


# Local paths for the workspace and the Minecraft server
PROJECT_DIR := $${HOME}/Documents/GitHub/McRemote/mcremote
MC_DIR := $${HOME}/MINECRAFT_SERVERS/PaperMC
# FTP settings for deployment
-include ftp_settings.mk
TARGETS ?= staging  # default target is staging

# Usage:
# == local development ==
# make build           # build the plugin
# make golive          # build, reload the plugin, then restart the local server
#   make restart-server  # restart the local server
#   make run             # start the local server
#   make stop            # stop the local server
#   make reload-plugin   # update the plugin in the local server
#   make build-deploy    # build and deploy(ftp) the plugin to the target server
# == local & GitHub Actions ==
# make deploy          # deploy(ftp) the plugin to the staging server
# make deploy TARGETS=production  # deploy to the production server
# make deploy TARGETS="staging production"  # deploy to multiple servers
# == GitHub Actions ==
# make trigger         # trigger GitHub Actions to build, deploy, and create a release

.PHONY: MC_VERSION

MC_VERSION:
	@echo $(MC_VERSION)

.PHONY: PLUGIN_VERSION
PLUGIN_VERSION:
	@echo $(PLUGIN_VERSION)


.PHONY: build restart-server run stop reload-plugin build-deploy deploy trigger

M920Q_DIR := "/run/user/1000/gvfs/smb-share\:server\=m920q.local\,share\=m920q-home/MINECRAFT_SERVERS/PaperMC/"
M710Q1_DIR = "/run/user/1000/gvfs/smb-share\:server\=m710q1.local\,share\=home/MINECRAFT_SERVER/PaperMC/"
# samba共有するときに"マシン名-home"という名前で共有しておくと便利だった。


# Local development

golive: reload-plugin restart-server  # build and check the plugin in the server

build:
#	@echo "Building plugin version $(MC_VERSION)-$(PLUGIN_VERSION)..."
	./gradlew clean build -PmcVersion=$(MC_VERSION) -PpluginVersion=$(PLUGIN_VERSION)

restart-server: stop run  # restart the server

run:
	cd $(MC_DIR) && \
	screen -dmS minecraft java -Xmx8G -Xms8G -jar paper.jar && \
	echo "Minecraft server started successfully."

stop:  # stop the server if it is running, then wait for 5 seconds
	cd $(MC_DIR) && \
	if screen -list | grep -q minecraft; then \
		screen -S minecraft -X stuff "stop\r"; \
		sleep 5; \
	else \
		echo "No screen session found for 'minecraft'"; \
	fi

reload-plugin: build  # update the plugin in the server
	(rm -rf "$(MC_DIR)/plugins/McRemote" || :) && \
	(rm -f "$(MC_DIR)/plugins/mc-remote*.jar" || :) && \
	cp "$(PROJECT_DIR)/build/libs/mc-remote-$(MC_VERSION)-$(PLUGIN_VERSION).jar" "$(MC_DIR)/plugins/"

build-deploy: build deploy

# Local & GitHub Actions

deploy:
	@echo "Deploying mc-remote-$(MC_VERSION)-$(PLUGIN_VERSION).jar to: $(TARGETS)"
	@for target in $(subst ,, $(TARGETS)); do \
		echo "Deploying to $$target..."; \
		if [ "$$target" = "staging" ]; then \
			echo "Processing staging-specific steps..."; \
			lftp ftp://$(FTP_USER):$(FTP_PASS)@$(FTP_HOST)$(FTP_PATH) -e "glob -a rm mc-remote*.jar; put build/libs/mc-remote-$(MC_VERSION)-$(PLUGIN_VERSION).jar; bye"; \
		elif [ "$$target" = "production" ]; then \
			echo "Processing production-specific steps..."; \
			# production 用のデプロイコマンドを記述 \
		else \
			echo "Processing default steps for $$target..."; \
			# その他ターゲット用の共通コマンドを記述 \
		fi; \
	done
		# または
		# scp build/libs/mc-remote-$(MC_VERSION)-$(PLUGIN_VERSION).jar $$FTP_USER@$$target:$$FTP_PATH/	done


# GitHub Actions

trigger:  # make triggerで、GitHub Actionsをトリガーして、ビルドからデプロイ、リリース作成まで
	@echo "Triggering GitHub Actions with version $(MC_VERSION)-$(PLUGIN_VERSION)"
	git tag v$(MC_VERSION)-$(PLUGIN_VERSION)
	git push origin v$(MC_VERSION)-$(PLUGIN_VERSION)
