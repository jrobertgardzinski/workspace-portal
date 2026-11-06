import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// jsdom is one document shared by the whole file, so a component left mounted by one test is still
// in the tree for the next — and getByText would then match the previous test's render instead of
// this one's. Unmount between tests so each starts on an empty page.
afterEach(cleanup);
