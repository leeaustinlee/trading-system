#!/usr/bin/env bash
set -e

if [ -f ".env" ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

mvn spring-boot:run -Dspring-boot.run.profiles=local
