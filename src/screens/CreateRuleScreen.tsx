import { useNavigation, useRoute, type RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Pressable, StyleSheet, Text, View } from 'react-native';
import { Segmented, SwitchRow, TextField } from '../components/Field';
import { Button, Card, Screen, ScreenHeader, SectionLabel } from '../components/ui';
import { SmsGateway } from '../native/SmsGateway';
import type { ProcessorDescriptor, Webhook } from '../native/types';
import type { RootStackParamList } from '../navigation/types';
import { useRuleDraftStore } from '../store/ruleDraft';
import { palette, radius, spacing, typography } from '../theme/theme';

type Nav = NativeStackNavigationProp<RootStackParamList>;

const MATCH_TYPES = [
  { label: 'Any', value: 'any' },
  { label: 'Contains', value: 'contains' },
  { label: 'Exact', value: 'exact' },
  { label: 'Regex', value: 'regex' },
];

const CreateRuleScreen: React.FC = () => {
  const nav = useNavigation<Nav>();
  const route = useRoute<RouteProp<RootStackParamList, 'CreateRule'>>();
  const editingId = route.params?.ruleId;
  const { draft, patch } = useRuleDraftStore();
  const [processors, setProcessors] = useState<ProcessorDescriptor[]>([]);
  const [webhooks, setWebhooks] = useState<Webhook[]>([]);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    SmsGateway.getProcessors().then(setProcessors).catch(() => {});
    SmsGateway.getWebhooks().then(setWebhooks).catch(() => {});
  }, []);

  const save = useCallback(async () => {
    if (saving) return;
    if (!draft.name?.trim()) {
      Alert.alert('Name required', 'Give the rule a name before saving.');
      return;
    }
    setSaving(true);
    try {
      await SmsGateway.saveRule({
        id: draft.id,
        name: draft.name,
        senderPattern: draft.senderPattern,
        senderMatchType: draft.senderMatchType,
        processorType: draft.processorType,
        template: draft.template || null,
        regex: draft.regex || null,
        config: draft.config || null,
        webhookId: draft.webhookId,
        payloadTemplate: draft.payloadTemplate || null,
        enabled: draft.enabled,
        priority: draft.priority,
        sampleMessages: JSON.stringify(draft.sampleMessages) as any,
      });
      // Success returns to the list where the saved rule now appears. Surface failures explicitly
      // rather than silently leaving the user on the form wondering whether it saved.
      nav.goBack();
    } catch (e: any) {
      Alert.alert('Could not save rule', e?.message ?? 'Unknown error');
    } finally {
      setSaving(false);
    }
  }, [draft, nav, saving]);

  const remove = async () => {
    if (editingId) {
      await SmsGateway.deleteRule(editingId);
      nav.goBack();
    }
  };

  const p = draft.processorType;

  return (
    <Screen>
      <ScreenHeader title={editingId ? 'Edit Rule' : 'Create Rule'} subtitle="Match → Process → Forward" />

      <SectionLabel>Identity</SectionLabel>
      <TextField label="Rule name" value={draft.name} onChangeText={t => patch({ name: t })} placeholder="bKash payments" autoCapitalize="sentences" />

      <SectionLabel>Sender matching</SectionLabel>
      <Segmented
        label="Match type"
        options={MATCH_TYPES}
        value={draft.senderMatchType}
        onChange={v => patch({ senderMatchType: v as any })}
      />
      {draft.senderMatchType !== 'any' && (
        <TextField
          label="Sender pattern"
          value={draft.senderPattern}
          onChangeText={t => patch({ senderPattern: t })}
          placeholder="bKash"
          hint="The sender address the rule applies to."
        />
      )}

      <SectionLabel>Processor</SectionLabel>
      <View style={styles.procRow}>
        {processors.map(proc => {
          const active = proc.type === p;
          return (
            <Pressable
              key={proc.type}
              onPress={() => patch({ processorType: proc.type })}
              style={[styles.procChip, active && styles.procChipActive]}>
              <Text style={[styles.procText, active && styles.procTextActive]}>{proc.displayName}</Text>
            </Pressable>
          );
        })}
      </View>

      {p === 'template' && (
        <Card>
          <TextField
            label="Template"
            value={draft.template}
            onChangeText={t => patch({ template: t })}
            placeholder="Cash In Tk {amount} from {phone}. TrxID {trxId}."
            multiline
            mono
            hint="Use {placeholders}. The regex is generated automatically on save."
          />
          <Button title="Train from existing SMS" variant="secondary" onPress={() => nav.navigate('Training')} />
        </Card>
      )}

      {p === 'regex' && (
        <TextField
          label="Regex (named groups)"
          value={draft.regex}
          onChangeText={t => patch({ regex: t })}
          placeholder="Tk (?<amount>[\\d,]+).*TrxID (?<trxId>\\w+)"
          multiline
          mono
        />
      )}

      {p === 'json' && (
        <TextField
          label="JSON path config"
          value={draft.config}
          onChangeText={t => patch({ config: t })}
          placeholder='{ "paths": { "amount": "data.amount" } }'
          multiline
          mono
          hint="Optional. Omit to flatten the whole JSON body."
        />
      )}

      {p === 'javascript' && (
        <TextField
          label="Script"
          value={draft.template}
          onChangeText={t => patch({ template: t })}
          placeholder={'const m = body.match(/Tk (\\d+)/);\nreturn { matched: !!m, data: { amount: m && m[1] } };'}
          multiline
          mono
          hint="Has sms, body, sender in scope. Return { matched, data }."
        />
      )}

      <SectionLabel>Destination</SectionLabel>
      <Card>
        <Pressable style={styles.whOption} onPress={() => patch({ webhookId: null })}>
          <Text style={styles.whText}>Default webhook</Text>
          {draft.webhookId == null && <Text style={styles.whCheck}>✓</Text>}
        </Pressable>
        {webhooks.map(w => (
          <Pressable key={w.id} style={styles.whOption} onPress={() => patch({ webhookId: w.id })}>
            <Text style={styles.whText} numberOfLines={1}>
              {w.name} · {w.url}
            </Text>
            {draft.webhookId === w.id && <Text style={styles.whCheck}>✓</Text>}
          </Pressable>
        ))}
      </Card>

      <SectionLabel>Payload template (optional)</SectionLabel>
      <TextField
        label="Body"
        value={draft.payloadTemplate}
        onChangeText={t => patch({ payloadTemplate: t })}
        placeholder={'{ "amount": "{{amount}}", "trxId": "{{trxId}}" }'}
        multiline
        mono
        hint="{{variables}} come from processor output, plus sender/body/deviceId."
      />

      <SwitchRow label="Enabled" value={draft.enabled} onValueChange={v => patch({ enabled: v })} />

      <Button title={saving ? 'Saving…' : 'Save rule'} onPress={save} loading={saving} style={{ marginTop: spacing.md }} />
      {editingId && (
        <Button title="Delete rule" variant="danger" onPress={remove} style={{ marginTop: spacing.sm }} />
      )}
    </Screen>
  );
};

const styles = StyleSheet.create({
  procRow: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm, marginBottom: spacing.md },
  procChip: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: radius.pill,
    paddingHorizontal: spacing.md,
    paddingVertical: 7,
    backgroundColor: palette.surfaceAlt,
  },
  procChipActive: { backgroundColor: palette.accentSoft, borderColor: palette.accent },
  procText: { ...typography.label, color: palette.textMuted },
  procTextActive: { color: palette.accent },
  whOption: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: spacing.md,
    borderBottomWidth: 1,
    borderBottomColor: palette.border,
  },
  whText: { ...typography.body, color: palette.text, flex: 1, paddingRight: spacing.md },
  whCheck: { color: palette.accent, fontWeight: '700' },
});

export default CreateRuleScreen;
