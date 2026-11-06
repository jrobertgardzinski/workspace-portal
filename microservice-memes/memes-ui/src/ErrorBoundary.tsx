import { Component, ErrorInfo, ReactNode } from 'react';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Typography from '@mui/material/Typography';

/**
 * The last line of defence: whatever a render throws, the visitor gets a page with words on it and
 * a way out, instead of a blank white document.
 *
 * This exists because of a measured failure, not a hypothesis. A 500 from the comment service used
 * to put a Spring error object into `comments`, `comments.map` threw a TypeError, React had nowhere
 * to hand it, and the whole root came off — gallery, sign-in panel, everything — with `#root`
 * literally empty (verified in the browser: rootHtmlLength=0). Reloading did not help while the
 * dependency was down. A boundary does not fix the read that lied; the reads guard their own shape
 * now (api.ts). It makes sure the NEXT such bug costs a paragraph rather than the whole product.
 *
 * A class component on purpose: `componentDidCatch` has no hook equivalent.
 */
export default class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // the console is where a developer looks; the user gets the panel below
    console.error('the gallery hit an unhandled error', error, info.componentStack);
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <Box sx={{ maxWidth: 560, mx: 'auto', mt: 6, px: 2 }}>
        <Alert
          severity="error"
          action={<Button color="inherit" size="small" onClick={() => window.location.reload()}>
            Try again
          </Button>}
        >
          <AlertTitle>The gallery tripped over something</AlertTitle>
          <Typography variant="body2">
            One of the services answered in a way this page did not expect. Nothing you did is
            lost — try again, and if it keeps happening one of the services is down.
          </Typography>
        </Alert>
      </Box>
    );
  }
}
