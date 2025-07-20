# McRemote / Naohiro2g

## forked from [wensheng / JuicyRaspberryPie](https://github.com/wensheng/JuicyRaspberryPie)

***
## =X=X=X= UNDER CONSTRUCTION =X=X=X=

Please visit https://mc-remote.com for the latest information.

## How to build and deploy the plugin
To deploy the plugin to the FTP server, you have to create the file named "ftp_settings.mk" with the content like below:

```
# server1
ftp1.FTP_USER := username
ftp1.FTP_PASS := password
ftp1.FTP_HOST := ftp.example.com:21
ftp1.FTP_PATH := /minecraft/plugins/

# server2
ftp2.FTP_USER := username
ftp2.FTP_PASS := password
ftp2.FTP_HOST := ftp.example.com:21
ftp2.FTP_PATH := /minecraft/plugins/
```
This file is referenced from settings.gradle.kts and is used in gradle task to deploy built plugin files. Note, however, that it should not be committed to the repository because it contains sensitive information such as FTP credentials.

You can verify that it has already been set to .gitignore.

