const expoConfig = require('eslint-config-expo/flat');

// Jest injects these rather than them being imported, so they have to be
// declared or every test file trips no-undef.
const jestGlobals = {
  jest: 'readonly',
  describe: 'readonly',
  it: 'readonly',
  test: 'readonly',
  expect: 'readonly',
  beforeAll: 'readonly',
  beforeEach: 'readonly',
  afterAll: 'readonly',
  afterEach: 'readonly',
};

module.exports = [
  ...expoConfig,
  {
    ignores: ['node_modules/**', '.expo/**', 'dist/**', 'coverage/**'],
  },
  {
    files: ['**/__tests__/**/*.{ts,tsx,js}', 'jest.setup.js'],
    languageOptions: { globals: jestGlobals },
  },
];
