import { useNavigation, useRoute, type RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import React, { useEffect, useState } from 'react';
import { Alert, StyleSheet, Text } from 'react-native';
import { SwitchRow, TextField } from '../components/Field';
import { Badge, Button, Card, Screen, ScreenHeader, SectionLabel } from '../components/ui';
import { SmsGateway } from '../native/SmsGateway';
import { pingUrl, type PingResult } from '../native/http';
import type { SendTestResult, WebhookInput } from '../native/types';
import type { RootStackParamList } from '../navigation/types';
import { palette, spacing, typography } from '../theme/theme';

type Nav = NativeStackNavigationProp<RootStackParamList>;

const WebhookEditScreen: React.FC = () => {
  const nav = useNavigation<Nav>();
  const route = useRoute<RouteProp<RootStackParamList, 'WebhookEdit'>>();
  const id = route.params?.webhookId;

  const [form, setForm] = useState<WebhookInput>({ name: '', url: '', headers: '', enabled: true, isDefault: false });
  const [hasBearer, setHasBearer] = useState(false);
  const [hasHmac, setHasHmac] = useState(false);
  const [bearer, setBearer] = useState('');
  const [hmac, setHmac] = useState('');
  const [test, setTest] = useState<SendTestResult | null>(null);
  const [ping, setPing] = useState<PingResult | null>(null);
  const [busy, setBusy] = useState<null | 'save' | 'test' | 'ping'>(null);
  const [savedTick, setSavedTick] = useState(false);

  useEffect(() => {
    if (!id) return;
    SmsGateway.getWebhooks().then(list => {
      const w = list.find(x => x.id === id);
      if (w) {
        setForm({ id: w.id, name: w.name, url: w.url, headers: w.headers ?? '', enabled: w.enabled, isDefault: w.isDefault });
        setHasBearer(w.hasBearer);
        setHasHmac(w.hasHmac);
      }
    });
  }, [id]);

  const buildPayload = (): WebhookInput => {
    const payload: WebhookInput = { ...form };
    if (bearer.length > 0) payload.bearerToken = bearer;
    if (hmac.length > 0) payload.hmacSecret = hmac;
    return payload;
  };

  // Persist and return the row id, writing it back into `form` so any later save/test UPDATEs the
  // same row instead of inserting a duplicate. This is what stops repeated taps (and Save-then-Test)
  // from creating multiple copies of the same webhook.
  const persist = async (): Promise<number> => {
    const newId = await SmsGateway.saveWebhook(buildPayload());
    setForm(prev => ({ ...prev, id: newId }));
    // Keep secret inputs from being re-sent (and re-written) on the next save.
    setBearer('');
    setHmac('');
    if (bearer.length > 0) setHasBearer(true);
    if (hmac.length > 0) setHasHmac(true);
    return newId;
  };

  const save = async () => {
    if (busy) return;
    setBusy('save');
    try {
      await persist();
      nav.goBack();
    } catch (e: any) {
      Alert.alert('Could not save', e?.message ?? 'Unknown error');
    } finally {
      setBusy(null);
    }
  };

  const sendTest = async () => {
    if (busy) return;
    setBusy('test');
    setTest(null);
    try {
      const savedId = await persist();
      setSavedTick(true);
      const sample = JSON.stringify({ event: 'test', deviceId: 'preview', timestamp: Date.now() });
      setTest(await SmsGateway.testWebhook(savedId, sample));
    } catch (e: any) {
      Alert.alert('Could not save & test', e?.message ?? 'Unknown error');
    } finally {
      setBusy(null);
    }
  };

  const remove = async () => {
    if (id) {
      await SmsGateway.deleteWebhook(id);
      nav.goBack();
    }
  };

  return (
    <Screen>
      <ScreenHeader title={id ? 'Edit Webhook' : 'Add Webhook'} />

      <TextField label="Name" value={form.name} onChangeText={t => setForm({ ...form, name: t })} placeholder="Primary CRM" autoCapitalize="sentences" />
      <TextField
        label="URL (https)"
        value={form.url}
        onChangeText={t => setForm({ ...form, url: t })}
        placeholder="https://api.example.com/sms"
        keyboardType="url"
        hint="HTTPS is enforced unless insecure mode is enabled in Settings."
      />

      <SectionLabel>Authentication</SectionLabel>
      <TextField
        label="Bearer token"
        value={bearer}
        onChangeText={setBearer}
        placeholder={hasBearer ? '•••••••• (set — leave blank to keep)' : 'optional'}
      />
      <TextField
        label="HMAC secret"
        value={hmac}
        onChangeText={setHmac}
        placeholder={hasHmac ? '•••••••• (set — leave blank to keep)' : 'optional'}
        hint="Adds X-Signature: sha256=… with X-Timestamp and X-Nonce for replay protection."
      />

      <SectionLabel>Custom headers (JSON)</SectionLabel>
      <TextField
        label="Headers"
        value={form.headers ?? ''}
        onChangeText={t => setForm({ ...form, headers: t })}
        placeholder='{ "X-Source": "store-7" }'
        multiline
        mono
      />

      <SwitchRow label="Enabled" value={!!form.enabled} onValueChange={v => setForm({ ...form, enabled: v })} />
      <SwitchRow
        label="Default webhook"
        description="Used by rules that don't pick a specific destination."
        value={!!form.isDefault}
        onValueChange={v => setForm({ ...form, isDefault: v })}
      />

      <Button
        title={busy === 'save' ? 'Saving…' : 'Save webhook'}
        onPress={save}
        loading={busy === 'save'}
        disabled={!!busy}
        style={{ marginTop: spacing.md }}
      />
      <Button
        title={busy === 'test' ? 'Saving & testing…' : 'Save & send test'}
        variant="secondary"
        onPress={sendTest}
        loading={busy === 'test'}
        disabled={!!busy}
        style={{ marginTop: spacing.sm }}
      />
      <Button
        title="Ping URL (reachability)"
        variant="ghost"
        disabled={!!busy}
        onPress={async () => {
          setBusy('ping');
          try {
            setPing(await pingUrl(form.url));
          } finally {
            setBusy(null);
          }
        }}
        style={{ marginTop: spacing.sm }}
      />

      {savedTick && !test && (
        <Card style={{ marginTop: spacing.md }}>
          <Badge label={form.id ? `SAVED · webhook #${form.id}` : 'SAVED'} color={palette.accent} />
        </Card>
      )}

      {ping && (
        <Card style={{ marginTop: spacing.md }}>
          <Badge label={ping.ok ? `REACHABLE · ${ping.status} · ${ping.ms}ms` : 'UNREACHABLE'} color={ping.ok ? palette.accent : palette.danger} />
          {ping.error && <Text style={styles.err}>{ping.error}</Text>}
        </Card>
      )}

      {test && (
        <Card style={{ marginTop: spacing.md }}>
          <Badge label={test.success ? `OK · ${test.statusCode}` : 'FAILED'} color={test.success ? palette.accent : palette.danger} />
          {test.error && <Text style={styles.err}>{test.error}</Text>}
        </Card>
      )}

      {id && <Button title="Delete webhook" variant="danger" onPress={remove} style={{ marginTop: spacing.sm }} />}
    </Screen>
  );
};

const styles = StyleSheet.create({
  err: { ...typography.body, color: palette.danger, marginTop: spacing.sm },
});

export default WebhookEditScreen;
