import { useState } from 'react';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { authHeader, jsonHeaders, request, SECURITY } from './api';

/** The refusal as security words it: the code, and for a wrong code how many tries are left. */
type Refusal = { status?: string; attemptsLeft?: number };

/**
 * What to tell the person, per refusal. "Wrong password." used to cover EVERY non-2xx here — a
 * throttled attempt, a 500, a service restart — so somebody whose password was right retyped it
 * until they gave up, which is the same defect security-ui already fixed on its side (P18 poz. 40).
 * It matters more since step-up gained a rate limit: 429 is now a routine answer, and it means
 * "wait", not "you typed it wrong".
 *
 * <p>The STATUS alone still cannot say which refusal it is: StepUpController answers 401 for a
 * wrong password, a wrong code, a code chain that has run out of attempts and a ticket that is no
 * longer valid alike. Only the body names it — and telling somebody to type another code into a
 * ticket that is already dead is the same defect wearing different clothes.
 */
const messageFor = (status: number, wrong: string, body: Refusal = {}): string => {
  switch (body.status) {
    case 'WRONG_PASSWORD': return 'Wrong password.';
    case 'WRONG_CODE':
      return `Wrong code${body.attemptsLeft != null ? ` — ${body.attemptsLeft} tries left` : ''}.`;
    case 'TOO_MANY_ATTEMPTS':
      return 'Too many wrong codes — that confirmation is closed. Start again with your password.';
    case 'INVALID_TICKET':
      return 'That confirmation is no longer valid — start again with your password.';
    case 'ENROL_A_FACTOR_FIRST':
      return 'This account has no second factor to confirm with — enrol one before deleting it.';
    default: break;
  }
  if (status === 401 || status === 403) return wrong;
  if (status === 429) return 'Too many attempts — wait a moment and try again.';
  return `Security answered ${status}. Please try again.`;
};

/** A ticket security has closed: nothing typed into it can work, so the wizard goes back a step. */
const ticketIsSpent = (body: Refusal): boolean =>
  body.status === 'TOO_MANY_ATTEMPTS' || body.status === 'INVALID_TICKET';

/** A request that never arrived says nothing about the password — and must not wedge the dialog. */
const UNREACHABLE = 'Could not reach the security service — check your connection and try again.';

interface Props {
  token: string;
  /** The signed-in address. It goes in the PATH, and that is what makes this a request to be
   *  forgotten rather than an administrator's act: the same route with somebody else's address
   *  asks for the ADMIN role instead. */
  email: string;
  onDeleted: () => void;
  onClose: () => void;
}

/**
 * The deletion wizard — and it no longer asks what should happen to the content, because that was
 * never this person's to choose. Closing your own account is the right to be forgotten, and there
 * is no ground on which a portal keeps the memes of somebody who asked to be forgotten because the
 * community up-voted them. It used to offer three options, one of which kept the popular ones.
 *
 * <p>Conditions still exist — delete, keep without the author, keep only what was voted up — but
 * they belong to the ADMIN closing SOMEBODY ELSE's account (a ban, house rules), where nobody is
 * exercising any right and the fate of the content is an ordinary business decision. That lives in
 * the admin panel; this dialog states plainly what will happen and asks only for proof of identity.
 */
export default function DeleteAccountDialog({ token, email, onDeleted, onClose }: Props) {
  const [busy, setBusy] = useState(false);
  // deleting is irreversible → step-up: confirm the password, then a factor code if one is enrolled
  const [password, setPassword] = useState('');
  const [stepUpTicket, setStepUpTicket] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);

  const doDelete = async () => {
    // your own address in the path, and no body: a rule sent from here would be dropped before it
    // reached the wire anyway — a self-requested closure destroys, and that is not configurable
    const r = await request(`${SECURITY}/account/${encodeURIComponent(email)}`, {
      method: 'DELETE',
      headers: authHeader(token),
    });
    if (r.status === 202) onDeleted();
    else setError(messageFor(r.status, 'Deletion was refused — please try again.'));
  };

  // step 1: prove the password (and open the factor chain if the account has one)
  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await request(`${SECURITY}/account/step-up`, {
        method: 'POST',
        headers: { ...jsonHeaders, ...authHeader(token) },
        body: JSON.stringify({ action: 'delete-account', password }),
      });
      const body: Refusal & { stepUpTicket?: string } = await r.json().catch(() => ({}));
      if (r.status === 200 && body.status === 'ELEVATED') await doDelete();
      else if (r.status === 202 && body.status === 'FACTOR_REQUIRED') setStepUpTicket(body.stepUpTicket!);
      else setError(messageFor(r.status, 'Wrong password.', body));
    } catch {
      setError(UNREACHABLE);
    } finally {
      // in a finally, because a throw used to skip it and leave every button disabled for good:
      // the only way out of the wizard was reloading the page
      setBusy(false);
    }
  };

  // step 2 (only if a factor is enrolled): the mailed/authenticator code completes the step-up
  const submitCode = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await request(`${SECURITY}/account/step-up/factor`, {
        method: 'POST',
        headers: { ...jsonHeaders, ...authHeader(token) },
        body: JSON.stringify({ stepUpTicket, proof: code }),
      });
      if (r.status === 200) {
        await doDelete();
      } else {
        const body: Refusal = await r.json().catch(() => ({}));
        setError(messageFor(r.status, 'Wrong code.', body));
        // back to the password field: a spent ticket accepts nothing, and leaving the code box in
        // front of somebody is an invitation to keep feeding a chain that is already over
        if (ticketIsSpent(body)) setStepUpTicket('');
      }
    } catch {
      setError(UNREACHABLE);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Delete your account</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          Your account locks immediately, and <b>everything you posted goes with it</b> — every
          meme, every comment, every saved favourite, and the votes you cast. There is nothing to
          choose here and nothing is kept: that is what being forgotten means.
        </Typography>
        <Typography variant="body2" sx={{ mt: 2 }}>Confirm it is you:</Typography>
        {!stepUpTicket ? (
          <TextField size="small" type="password" label="your password" fullWidth sx={{ mt: 1 }}
            value={password} onChange={(e) => setPassword(e.target.value)} />
        ) : (
          <TextField size="small" label="sign-in code" fullWidth sx={{ mt: 1 }}
            value={code} onChange={(e) => setCode(e.target.value)} autoFocus />
        )}
        {error && <Typography variant="body2" color="error" sx={{ mt: 1 }}>{error}</Typography>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Keep my account</Button>
        {!stepUpTicket ? (
          <Button color="error" variant="contained" disabled={busy || !password} onClick={() => void submit()}>
            Delete my account
          </Button>
        ) : (
          <Button color="error" variant="contained" disabled={busy || !code} onClick={() => void submitCode()}>
            Confirm & delete
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
}
