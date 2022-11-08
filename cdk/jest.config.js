module.exports = {
  testMatch: ["<rootDir>/lib/**/*.test.ts"],
  transform: {
    "^.+\\.tsx?$": "ts-jest",
  },
  setupFilesAfterEnv: ["./jest.setup.js"],

  // Preserve snapshot format during initial Jest 29 upgrade.
  // See https://jestjs.io/docs/upgrading-to-jest29.
  // TODO remove this!
  snapshotFormat: {
    escapeString: true,
    printBasicPrototype: true,
  },
};
