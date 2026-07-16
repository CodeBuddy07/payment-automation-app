import React, { useEffect, useState } from 'react';
import { Image, StyleSheet, Text, View } from 'react-native';
import { Card, KeyValue, Screen, ScreenHeader, SectionLabel } from '../components/ui';
import { SmsGateway } from '../native/SmsGateway';
import type { DeviceInfo } from '../native/types';
import { palette, radius, spacing, typography } from '../theme/theme';

const PIPELINE = ['Incoming SMS', 'Rule match', 'Processor', 'Payload builder', 'Offline queue', 'Webhook sender'];

const AboutScreen: React.FC = () => {
  const [device, setDevice] = useState<DeviceInfo | null>(null);

  useEffect(() => {
    SmsGateway.getDeviceInfo().then(setDevice).catch(() => {});
  }, []);

  return (
    <Screen>
      <Image source={require('../../assets/brand/logo.png')} style={styles.brand} resizeMode="contain" />
      <ScreenHeader title="About" subtitle="SMS Gateway Agent" />

      <Card style={{ borderColor: palette.accentDim }}>
        <Text style={styles.vision}>
          A standalone, backend-free Android agent that observes incoming SMS, processes them through
          configurable rules and processors, and forwards structured data to your webhooks — for
          payment verification, OTP forwarding, bank monitoring, alerts and any SMS-driven workflow.
        </Text>
      </Card>

      <SectionLabel>Pipeline</SectionLabel>
      <Card>
        {PIPELINE.map((step, i) => (
          <View key={step} style={styles.step}>
            <View style={styles.stepDot}>
              <Text style={styles.stepNum}>{i + 1}</Text>
            </View>
            <Text style={styles.stepText}>{step}</Text>
          </View>
        ))}
      </Card>

      <SectionLabel>Device</SectionLabel>
      <Card>
        <KeyValue label="Model" value={device ? `${device.manufacturer} ${device.model}` : '—'} />
        <KeyValue label="Android" value={device ? `${device.androidVersion} (SDK ${device.sdkInt})` : '—'} />
        <KeyValue label="App version" value={device?.appVersion} />
        <KeyValue label="Device ID" value={device?.deviceId} mono />
        <KeyValue label="Installation ID" value={device?.installationId} mono />
      </Card>

      <Text style={styles.footer}>Configurable without code changes · Reusable commercial-grade product</Text>
    </Screen>
  );
};

const styles = StyleSheet.create({
  brand: { width: 64, height: 64, borderRadius: 14, alignSelf: 'center', marginTop: spacing.md, marginBottom: spacing.xs },
  vision: { ...typography.body, color: palette.textMuted, lineHeight: 21 },
  step: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, paddingVertical: spacing.xs },
  stepDot: {
    width: 26,
    height: 26,
    borderRadius: radius.sm,
    backgroundColor: palette.accentSoft,
    borderWidth: 1,
    borderColor: palette.accentDim,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stepNum: { color: palette.accent, fontWeight: '700', fontSize: 12 },
  stepText: { ...typography.body, color: palette.text },
  footer: { ...typography.body, color: palette.textFaint, textAlign: 'center', marginTop: spacing.lg, fontSize: 12 },
});

export default AboutScreen;
