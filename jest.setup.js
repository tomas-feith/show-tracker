// AsyncStorage is a native module, so importing it under Jest throws unless it
// is replaced with the in-memory mock the package ships for exactly this.
jest.mock('@react-native-async-storage/async-storage', () =>
  require('@react-native-async-storage/async-storage/jest/async-storage-mock')
);
