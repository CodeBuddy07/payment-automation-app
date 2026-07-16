import { useRoute, type RouteProp } from '@react-navigation/native';
import React, { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { TextField } from '../components/Field';
import { Badge, Button, Card, Screen, ScreenHeader, SectionLabel } from '../components/ui';
import { SmsGateway } from '../native/SmsGateway';
import type { Rule, RuleTestResult } from '../native/types';
import type { RootStackParamList } from '../navigation/types';
import { palette, spacing, typography } from '../theme/theme';
import { prettyJson, truncate } from '../utils/format';

const RuleTestingScreen: React.FC = () => {
  const route = useRoute<RouteProp<RootStackParamList, 'RuleTesting'>>();
  const ruleId = route.params.ruleId;
  const [rule, setRule] = useState<Rule | null>(null);
  const [sender, setSender] = useState('');
  const [body, setBody] = useState('');
  const [single, setSingle] = useState<RuleTestResult | null>(null);
  const [history, setHistory] = useState<{ totalMatches: number; samples: any[] } | null>(null);

  useEffect(() => {
    SmsGateway.getRules().then(rules => {
      const found = rules.find(r => r.id === ruleId) ?? null;
      setRule(found);
      if (found) setSender(found.senderPattern);
    });
  }, [ruleId]);

  const runSingle = async () => {
    if (!rule) return;
    setSingle(await SmsGateway.testRule(rule, sender, body));
  };

  const runHistory = async () => {
    if (!rule) return;
    setHistory(await SmsGateway.testRuleAgainstHistory(rule));
  };

  return (
    <Screen>
      <ScreenHeader title="Rule Testing" subtitle={rule?.name ?? '—'} />

      <SectionLabel>Test a message</SectionLabel>
      <TextField label="Sender" value={sender} onChangeText={setSender} placeholder="bKash" />
      <TextField
        label="Body"
        value={body}
        onChangeText={setBody}
        placeholder="Cash In Tk 500 from 01711111111. TrxID ABC12345."
        multiline
      />
      <Button title="Run test" onPress={runSingle} />

      {single && (
        <Card style={{ marginTop: spacing.md }}>
          <Badge
            label={single.matched ? 'MATCHED' : 'NO MATCH'}
            color={single.matched ? palette.accent : palette.danger}
          />
          {single.reason && <Text style={styles.reason}>{single.reason}</Text>}
          {single.matched && single.data && (
            <>
              <SectionLabel>Extracted data</SectionLabel>
              <Text style={styles.json}>{prettyJson(single.data)}</Text>
            </>
          )}
          {!!single.errors?.length && <Text style={styles.err}>{single.errors.join('\n')}</Text>}
        </Card>
      )}

      <SectionLabel>Test against history</SectionLabel>
      <Button title="Run on stored messages" variant="secondary" onPress={runHistory} />
      {history && (
        <Card style={{ marginTop: spacing.md }}>
          <Text style={styles.count}>{history.totalMatches} matches</Text>
          {history.samples.map((s, i) => (
            <View key={i} style={styles.sample}>
              <Text style={styles.sampleBody}>{truncate(String(s.body), 90)}</Text>
              <Text style={styles.json}>{prettyJson(s.data)}</Text>
            </View>
          ))}
        </Card>
      )}
    </Screen>
  );
};

const styles = StyleSheet.create({
  reason: { ...typography.body, color: palette.textMuted, marginTop: spacing.sm },
  json: { ...typography.mono, color: palette.accent, marginTop: spacing.sm },
  err: { ...typography.body, color: palette.danger, marginTop: spacing.sm },
  count: { ...typography.title, color: palette.accent, marginBottom: spacing.sm },
  sample: { borderTopWidth: 1, borderTopColor: palette.border, paddingTop: spacing.sm, marginTop: spacing.sm },
  sampleBody: { ...typography.body, color: palette.textMuted },
});

export default RuleTestingScreen;
