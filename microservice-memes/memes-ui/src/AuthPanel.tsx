import { useEffect, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Link from '@mui/material/Link';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Tab from '@mui/material/Tab';
import Tabs from '@mui/material/Tabs';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import MarkEmailReadIcon from '@mui/icons-material/MarkEmailRead';
import DeleteAccountDialog from './DeleteAccountDialog';
import { jsonHeaders, Notice, SECURITY } from './api';

type Mode = 'signin' | 'signup' | 'inbox' | 'mfa';

const prettify = (code: string) => code.toLowerCase().replaceAll('_', ' ');

// Both channels of a refused registration speak one shape: {CODE: parameter}, where the parameter
// is the policy value in force for THIS attempt (the minimum length is live configuration) or true
// for a rule that has none. A plain string is still accepted, so an older service is readable too.
type FieldError = string | Record<string, unknown>;
const prettifyFieldError = (error: FieldError): string => {
  if (typeof error === 'string') return prettify(error);
  return Object.entries(error)
    .map(([code, parameter]) => (parameter === true ? prettify(code) : `${prettify(code)}: ${String(parameter)}`))
    .join(', ');
};

interface Props {
  token: string | null;
  user: string;
  onToken: (token: string) => void;
  onLogout: () => void;
}

export default function AuthPanel({ token, user, onToken, onLogout }: Props) {
  const [mode, setMode] = useState<Mode>('signin');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [notice, setNotice] = useState<Notice | null>(null);
  const [busy, setBusy] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [mfaTicket, setMfaTicket] = useState('');
  const [code, setCode] = useState('');
  const [providers, setProviders] = useState<{ name: string; label: string }[]>([]);

  // one button per configured provider — adding one to security's config is all it takes
  useEffect(() => {
    void fetch(`${SECURITY}/oauth/providers`)
      .then((r) => (r.ok ? r.json() : { providers: [] }))
      .then((body) => setProviders(body.providers ?? []))
      .catch(() => setProviders([]));
  }, []);

  // the OAuth callback lands here with the access token in the FRAGMENT (never sent to a
  // server); the refresh token already sits in security's HttpOnly cookie
  useEffect(() => {
    const fragment = new URLSearchParams(location.hash.replace(/^#/, ''));
    const oauthToken = fragment.get('accessToken');
    const oauthTicket = fragment.get('mfaTicket');
    const oauthError = fragment.get('oauthError');
    if (!oauthToken && !oauthTicket && !oauthError) return;
    history.replaceState(null, '', location.pathname + location.search);
    if (oauthToken) onToken(oauthToken);
    else if (oauthTicket) {
      // the provider login was link #1; the account has more factors — finish the chain here
      setMfaTicket(oauthTicket);
      setCode('');
      setMode('mfa');
    } else {
      setNotice({ tone: 'warning', text: `Social sign-in did not complete (${prettify(oauthError!)}).` });
    }
  }, [onToken]);

  // the verification mail links here (?verify=<token>); confirm it on arrival
  useEffect(() => {
    const mailed = new URLSearchParams(location.search).get('verify');
    if (!mailed) return;
    history.replaceState(null, '', location.pathname);
    void fetch(`${SECURITY}/verify-email`, {
      method: 'POST',
      headers: jsonHeaders,
      body: JSON.stringify({ token: mailed }),
    }).then((r) =>
      setNotice(
        r.ok
          ? { tone: 'success', text: 'E-mail verified — sign in below.' }
          : { tone: 'warning', text: 'This verification link was already used or replaced by a newer one.' },
      ),
    );
  }, []);

  if (token) {
    return (
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
        <Chip
          label={`signed in as ${user || '…'}`}
          color="primary"
          variant="outlined"
          onDelete={onLogout}
          deleteIcon={<span style={{ fontSize: '0.8rem', padding: '0 6px' }}>sign out</span>}
        />
        <Button size="small" color="error" onClick={() => setDeleting(true)}>delete account…</Button>
        {deleting && (
          <DeleteAccountDialog
            token={token}
            email={user}
            onClose={() => setDeleting(false)}
            onDeleted={() => {
              setDeleting(false);
              setNotice({
                tone: 'success',
                text: 'Account deletion started — everything you posted is being removed; the goodbye mail will confirm.',
              });
              onLogout();
            }}
          />
        )}
      </Stack>
    );
  }

  const signUp = async () => {
    const r = await fetch(`${SECURITY}/register`, {
      method: 'POST',
      headers: jsonHeaders,
      body: JSON.stringify({ email, password }),
    });
    if (r.status === 201) {
      // security answers 201 for taken addresses too (anti-enumeration) —
      // the mail tells the owner whether it is a verification link or "you already have an account"
      setMode('inbox');
    } else if (r.status === 422) {
      const errors: { emailErrors?: FieldError[]; passwordErrors?: FieldError[] } = await r.json();
      // one shape, one renderer — each field shows only the rules that attempt actually broke
      const sections = [
        { title: 'e-mail', items: (errors.emailErrors ?? []).map(prettifyFieldError) },
        { title: 'password', items: (errors.passwordErrors ?? []).map(prettifyFieldError) },
      ].filter((section) => section.items.length > 0);
      setNotice({ tone: 'warning', text: 'That will not do:', sections });
    } else {
      setNotice({ tone: 'warning', text: `Registration failed (${r.status}).` });
    }
  };

  const signIn = async () => {
    const r = await fetch(`${SECURITY}/authenticate`, {
      method: 'POST',
      headers: jsonHeaders,
      // security answers with the refresh token in an HttpOnly cookie, and this UI is a DIFFERENT
      // origin from security (8083 vs 8080) — without credentials:'include' the browser drops that
      // Set-Cookie on the floor, and an hour later there is nothing left to refresh the session
      // with. CORS is already set up for it (allowed-origins + Allow-Credentials: true).
      credentials: 'include',
      body: JSON.stringify({ email, password }),
    });
    if (r.status === 200) {
      // 200, not r.ok: a 202 is ALSO "ok" to fetch, and reading it as success made the whole
      // second-factor branch below dead code — the account with a factor was handed an
      // undefined token instead of the code step (the same trap the sign-in UI fell into once)
      const body: { accessToken: string } = await r.json();
      onToken(body.accessToken);
    } else if (r.status === 202) {
      // more sign-in factors owed; the first code is on its way to the mailbox
      const body: { mfaTicket: string } = await r.json();
      setMfaTicket(body.mfaTicket);
      setCode('');
      setMode('mfa');
    } else if (r.status === 403) {
      setNotice({ tone: 'warning', text: 'E-mail not verified yet — click the link in the mail (inbox: Mailpit, localhost:8025).' });
    } else if (r.status === 429) {
      setNotice({ tone: 'warning', text: 'Too many failed attempts from this machine — blocked for a few minutes.' });
    } else if (r.status === 401) {
      setNotice({ tone: 'warning', text: 'Wrong e-mail or password.' });
    } else {
      // Security's refusals are 401, 403 and 429 and nothing else (AuthenticationController) —
      // so what lands here is a FAULT: its database down, a gateway in front of it, a 500. Calling
      // that a wrong password sends somebody to retype a password that was right until the
      // per-source throttle blocks them for it
      setNotice({ tone: 'warning', text: `The sign-in service is having trouble (${r.status}) — this is not about your password; try again in a moment.` });
    }
  };

  const submitFactor = async () => {
    try {
      await sendFactor();
    } catch {
      // the second step of a sign-in is where a silent failure hurts most: the code expires while
      // the user waits for a screen that will never change
      setNotice({ tone: 'warning', text: 'Could not reach the sign-in service — try again in a moment.' });
    }
  };

  const sendFactor = async () => {
    const r = await fetch(`${SECURITY}/authenticate/factor`, {
      method: 'POST',
      headers: jsonHeaders,
      credentials: 'include',   // the refresh cookie is issued here too, at the end of the chain
      body: JSON.stringify({ mfaTicket, proof: code }),
    });
    if (r.status === 200) {
      const body: { accessToken: string } = await r.json();
      onToken(body.accessToken);
    } else if (r.status === 202) {
      // a chain longer than one link: this factor is satisfied, the next one is owed
      const body: { mfaTicket: string } = await r.json();
      setMfaTicket(body.mfaTicket);
      setCode('');
    } else {
      const body: { status?: string; attemptsLeft?: number } = await r.json();
      if (body.status === 'WRONG_CODE') {
        setNotice({ tone: 'warning', text: `Wrong code${body.attemptsLeft != null ? ` — ${body.attemptsLeft} tries left` : ''}.` });
      } else {
        setNotice({ tone: 'warning', text: 'That sign-in expired — start over.' });
        setMode('signin');
      }
    }
  };

  const submit = async () => {
    setBusy(true);
    setNotice(null);
    try {
      await (mode === 'signup' ? signUp() : signIn());
    } catch {
      // `finally` alone put the button back and said nothing, so an unreachable security service
      // looked exactly like a form the user had not filled in yet: they press it again, and again.
      // Naming the service is the difference between "try again" and "try again LATER".
      setNotice({ tone: 'warning', text: 'Could not reach the sign-in service — try again in a moment.' });
    } finally {
      setBusy(false);
    }
  };

  if (mode === 'mfa') {
    return (
      <Paper sx={{ p: 2, maxWidth: 430 }}>
        <Box component="form" onSubmit={(e) => { e.preventDefault(); void submitFactor(); }}>
          <Stack spacing={1.5}>
            <Typography>One more step — we e-mailed a sign-in code to <b>{email}</b>.</Typography>
            <TextField label="sign-in code" size="small" autoFocus
                       value={code} onChange={(e) => setCode(e.target.value)} />
            <Button type="submit" variant="contained" disabled={!code}>Sign in</Button>
            <Button variant="text" onClick={() => setMode('signin')}>Start over</Button>
            <Typography variant="caption" color="text.secondary">
              Lost access? Type one of your recovery codes instead — it works once.
            </Typography>
          </Stack>
        </Box>
        {notice && <Alert severity={notice.tone} sx={{ mt: 1.5 }}>{notice.text}</Alert>}
      </Paper>
    );
  }

  if (mode === 'inbox') {
    return (
      <Paper sx={{ p: 2, maxWidth: 430 }}>
        <Stack spacing={1.5} sx={{ alignItems: 'flex-start' }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <MarkEmailReadIcon color="primary" />
            <Typography>
              Almost there — check the mail we sent to <b>{email}</b>.
            </Typography>
          </Stack>
          <Typography variant="body2" color="text.secondary">
            Open the inbox (<Link href="http://localhost:8025" target="_blank">Mailpit</Link>) and
            follow the mail — a verification link brings you back here ready to sign in; if the
            address already had an account, the mail says so instead.
          </Typography>
          <Button variant="outlined" onClick={() => setMode('signin')}>
            I clicked it — sign in
          </Button>
        </Stack>
      </Paper>
    );
  }

  return (
    <Paper sx={{ p: 2, maxWidth: 430 }}>
      <Tabs value={mode} onChange={(_, m: Mode) => { setMode(m); setNotice(null); }} sx={{ mb: 1.5 }}>
        <Tab label="Sign in" value="signin" />
        <Tab label="Create account" value="signup" />
      </Tabs>
      {/*
        * noValidate: the browser does not get a vote on what an address is. Its type="email" rule is
        * ASCII-only, so it blocked addresses security ACCEPTS (Józef@wp.pl is a valid EAI address) and
        * waved through ones it refuses (x@wp) — a second, disagreeing authority whose verdict arrives
        * as a native bubble the panel cannot style or explain. type="email" stays for the phone
        * keyboard; the judgement is the server's alone, and its refusal lands in the notice below with
        * every other rule the attempt broke.
        */}
      <Box component="form" noValidate onSubmit={(e) => { e.preventDefault(); void submit(); }}>
        <Stack spacing={1.5}>
          <TextField
            label="e-mail" type="email" size="small" autoComplete="email"
            value={email} onChange={(e) => setEmail(e.target.value)}
          />
          <TextField
            label="password" type="password" size="small"
            autoComplete={mode === 'signup' ? 'new-password' : 'current-password'}
            value={password} onChange={(e) => setPassword(e.target.value)}
          />
          <Button type="submit" variant="contained" disabled={busy || !email || !password}>
            {mode === 'signup' ? 'Create account' : 'Sign in'}
          </Button>
          {notice && (
            <Alert severity={notice.tone}>
              {notice.text}
              {notice.items && (
                <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>
                  {notice.items.map((item) => <li key={item}>{item}</li>)}
                </ul>
              )}
              {notice.sections?.map((section) => (
                <Box key={section.title} sx={{ mt: 0.5 }}>
                  <strong>{section.title}</strong>
                  <ul style={{ margin: '2px 0 0', paddingLeft: 18 }}>
                    {section.items.map((item) => <li key={item}>{item}</li>)}
                  </ul>
                </Box>
              ))}
            </Alert>
          )}
          {providers.map((provider) => (
            <Button
              key={provider.name}
              variant="outlined"
              onClick={() => {
                // security drives the whole OAuth dance; in the local stack the providers are stub IdPs
                const back = encodeURIComponent(location.origin + location.pathname);
                location.href = `${SECURITY}/oauth/${provider.name}/start?return=${back}`;
              }}
            >
              Sign in with {provider.label}
            </Button>
          ))}
        </Stack>
      </Box>
    </Paper>
  );
}
