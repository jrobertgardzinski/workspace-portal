#!/bin/bash
# The unit suite's Node floor, said out loud BEFORE vitest starts — modelled on
# ../../e2e/require-node.sh, for the same reason: a version failure that arrives as a stack trace
# from a transitive dependency tells the reader nothing about the real problem.
#
# Why it exists. package.json has declared `engines: node >= 22.14` for a while, and npm honours
# engines at INSTALL time only (and only with engine-strict). `npm test` on Node 20 therefore does
# not say "wrong Node": jsdom loads undici, undici calls webidl.util.markAsUncloneable, that
# function does not exist before Node 20.18/22, and vitest reports
#
#   Failed to start forks worker ... TypeError: webidl.util.markAsUncloneable is not a function
#
# which reads like a broken test runner. It cost a real half-hour on 2026-07-29, on a machine whose
# nvm default was 20 while the Maven build and CI both use 22 — so the same command passed inside
# `mvn verify` and failed in a shell, which is the most confusing shape a version problem can take.
#
# Kept as a script rather than inlined into a pretest hook, so it can be run on its own when you
# just want to know: ./require-node.sh
set -euo pipefail

node -e '
const required = 22;
const [major] = process.versions.node.split(".").map(Number);
if (major < required) {
  console.error(`FAIL: memes-ui needs Node >= ${required} (package.json engines says 22.14), found ${process.versions.node}.`);
  console.error("       jsdom/undici will die with \"markAsUncloneable is not a function\" instead of saying so.");
  console.error("       The Maven build provisions its own Node at memes-ui/target/node — CI uses 22 too:");
  console.error("         PATH=\"$PWD/target/node:$PATH\" npm test");
  process.exit(1);
}'
