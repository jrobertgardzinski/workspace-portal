const common = {
  paths: ['features/*.feature'],
  import: ['support/*.mjs', 'steps/*.mjs'],
  format: ['progress'],
};

export default {
  ...common,
  // @outage stops and starts real compose containers and takes minutes to
  // watch the saga capitulate — it runs only through ../e2e-saga-outage.sh
  // (cucumber-js --profile outage)
  tags: 'not @outage',
};

export const outage = {
  ...common,
  tags: '@outage',
};
