#!/bin/sh

if [ ! -n "$1" ]
then
    echo 'Must provide PaperMC Version number such as "build.sh 1.21.4"'
else
    mvn -DPaperVersion=$1 clean package
fi
