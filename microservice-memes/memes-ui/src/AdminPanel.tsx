import { useEffect, useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { clearPurgePolicy, getPurgePolicy, PurgePolicy, setPurgePolicy } from './api';

const KEEP_POPULAR = 'KEEP_POPULAR_ANONYMIZED';

const UNREAD_POLICY = 'Could not read the current policy — the dial below is NOT what is in force.';

/**
 * The administrator's dial over the purge-policy default: what happens to a leaver's memes when the
 * closure that took them away stated no rule of its own. The backend is the authority — refusals
 * (bad rule, not an admin) come back as messages, never enforced client-side.
 *
 * <p>It used to say "the leaver's own choice always wins over this dial", and that stopped being
 * true when the deletion wizard lost its choices: somebody closing their own account states
 * nothing and every meme goes, dial or no dial. What this dial still answers is the closure an
 * ADMIN starts without naming a rule.
 */
export default function AdminPanel({ token, open, onClose }: {
  token: string | null;
  open: boolean;
  onClose: () => void;
}) {
  const [policy, setPolicy] = useState<PurgePolicy | null>(null);
  const [kind, setKind] = useState('DELETE');
  const [minScore, setMinScore] = useState(100);
  const [notice, setNotice] = useState<string | null>(null);

  const load = () => void getPurgePolicy(token).then((p) => {
    setPolicy(p);
    if (p) {
      // a split always yields a first part, but the compiler has no way to know that (indexing a
      // string[] is `string | undefined` under noUncheckedIndexedAccess) — so the default is stated
      const [rule = 'DELETE', threshold] = p.effective.split(':');
      setKind(rule);
      if (threshold) setMinScore(Number(threshold));
    } else {
      // a REFUSED read (403 not an admin, a 500, a 401 that outlived its refresh) arrives as null,
      // and hiding the "Effective:" alert was all that happened: the dial kept its initial DELETE
      // and presented it, unlabelled, as the rule in force — so "Save override" wrote a rule
      // nobody had chosen. An unread policy is exactly as unknown as an unreachable one
      setNotice(UNREAD_POLICY);
    }
  }).catch(() => setNotice(UNREAD_POLICY));
  useEffect(() => { if (open) { setNotice(null); load(); } }, [open]);   // eslint-disable-line react-hooks/exhaustive-deps

  const save = async () => {
    const rule = kind === KEEP_POPULAR ? `${KEEP_POPULAR}:${minScore}` : kind;
    try {
      const r = await setPurgePolicy(rule, token);
      setNotice(r.ok ? 'Override saved — purges follow it from now on.' : `Refused: ${r.detail}`);
      load();
    } catch {
      // the dangerous silence: the admin sets a rule, sees nothing, and walks away believing the
      // purge policy changed. Note there is no re-read on this path — the service that just failed
      // to take the write is not going to answer a read, and its failure notice would overwrite
      // this one, leaving the weaker of the two sentences on screen.
      setNotice('The service did not answer — the override was NOT saved.');
    }
  };

  const reset = async () => {
    try {
      setNotice((await clearPurgePolicy(token))
        ? 'Override cleared — the deployment default applies again.'
        : 'Refused.');
      load();
    } catch {
      setNotice('The service did not answer — the override was NOT cleared.');
    }
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Admin — purge policy</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Typography variant="body2" color="text.secondary">
            What happens to a leaver&apos;s memes when an admin closes their account without
            naming a rule. It never applies to somebody closing their OWN account — that always
            deletes everything.
          </Typography>
          {policy && (
            <Alert severity="info">
              Effective: <b>{policy.effective}</b> (from {policy.source === 'DB' ? 'admin override' : 'deployment env'};
              env default {policy.envDefault})
            </Alert>
          )}
          <TextField select label="Rule" value={kind} onChange={(e) => setKind(e.target.value)}>
            <MenuItem value="DELETE">DELETE — memes disappear</MenuItem>
            <MenuItem value="ANONYMIZE_AUTHOR">ANONYMIZE_AUTHOR — memes stay, author anonymised</MenuItem>
            <MenuItem value={KEEP_POPULAR}>KEEP_POPULAR_ANONYMIZED — favourites stay</MenuItem>
          </TextField>
          {kind === KEEP_POPULAR && (
            <TextField type="number" label="Minimum score to keep" value={minScore}
                       slotProps={{ htmlInput: { min: 1 } }}
                       onChange={(e) => setMinScore(Math.max(1, Number(e.target.value) || 1))} />
          )}
          {/* only a write that LANDED is good news; everything else here is a refusal, a fault or
              a policy nobody could read, and a green alert over any of those is the same lie the
              confident DELETE was */}
          {notice && <Alert severity={notice.startsWith('Override') ? 'success' : 'warning'}>{notice}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={() => void reset()}>Use env default</Button>
        <Button onClick={() => void save()} variant="contained">Save override</Button>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}
