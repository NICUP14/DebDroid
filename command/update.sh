#!/bin/sh

# Check if the user has root permissions 
if [ "$(id -u)" -ne 0 ]; then
    echo "$0: Missing required super-user permissions."
    exit 1
fi

apt update && apt upgrade