import React, { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Badge, Card, Screen, ScreenHeader, SectionLabel } from '../components/ui';
import { SmsGateway } from '../native/SmsGateway';
import type { ProcessorDescriptor } from '../native/types';
import { palette, spacing, typography } from '../theme/theme';

const DESCRIPTIONS: Record<string, string> = {
  raw: 'Forwards the message unchanged. Always matches — useful for plain forwarding.',
  template: 'Matches a {placeholder} template (trainable from real SMS) and extracts named fields.',
  regex: 'Runs a custom regular expression; named groups become extracted data.',
  json: 'Parses a JSON object embedded in the body and maps fields via JSON paths.',
  javascript: 'Runs sandboxed JavaScript (Rhino) to transform the SMS — even when the app is killed.',
};

const ProcessorManagementScreen: React.FC = () => {
  const [processors, setProcessors] = useState<ProcessorDescriptor[]>([]);

  useEffect(() => {
    SmsGateway.getProcessors().then(setProcessors).catch(() => {});
  }, []);

  return (
    <Screen>
      <ScreenHeader title="Processors" subtitle={`${processors.length} registered`} />

      <Card style={{ borderColor: palette.accentDim }}>
        <Text style={styles.note}>
          Processors are pluggable. New processors (Payment, AI, HTTP, custom) register at startup
          without changing rules or the UI.
        </Text>
      </Card>

      <SectionLabel>Available</SectionLabel>
      {processors.map(p => (
        <Card key={p.type}>
          <View style={styles.head}>
            <Text style={styles.name}>{p.displayName}</Text>
            <Badge label={p.type.toUpperCase()} color={palette.info} />
          </View>
          <Text style={styles.desc}>{DESCRIPTIONS[p.type] ?? 'Custom processor.'}</Text>
        </Card>
      ))}
    </Screen>
  );
};

const styles = StyleSheet.create({
  note: { ...typography.body, color: palette.textMuted },
  head: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: spacing.sm },
  name: { ...typography.heading, color: palette.text },
  desc: { ...typography.body, color: palette.textMuted },
});

export default ProcessorManagementScreen;
