import { useFocusEffect } from '@react-navigation/native';
import React, { useCallback, useState } from 'react';
import { Alert, Pressable, StyleSheet, Text, View } from 'react-native';
import { SwitchRow, TextField } from '../components/Field';
import { Badge, Button, Card, EmptyState, Screen, ScreenHeader, SectionLabel } from '../components/ui';
import { SmsGateway } from '../native/SmsGateway';
import type { ServiceTag, SimConfig, SimEvent, StationProfile } from '../native/types';
import { palette, radius, spacing, typography } from '../theme/theme';
import { relativeTime } from '../utils/format';

/** A selectable / removable pill. */
const Chip: React.FC<{
  label: string;
  active?: boolean;
  onPress?: () => void;
  onRemove?: () => void;
}> = ({ label, active, onPress, onRemove }) => (
  <Pressable
    onPress={onPress}
    onLongPress={onRemove}
    style={({ pressed }) => [
      styles.chip,
      active ? styles.chipActive : styles.chipIdle,
      pressed && { opacity: 0.85 },
    ]}>
    <Text style={[styles.chipText, active && styles.chipTextActive]}>{label}</Text>
  </Pressable>
);

const StationSetupScreen: React.FC = () => {
  const [profile, setProfile] = useState<StationProfile | null>(null);
  const [stationName, setStationName] = useState('');
  const [services, setServices] = useState<ServiceTag[]>([]);
  const [sims, setSims] = useState<SimConfig[]>([]);
  const [history, setHistory] = useState<SimEvent[]>([]);
  const [newService, setNewService] = useState('');
  const [saving, setSaving] = useState(false);
  const [rescanning, setRescanning] = useState(false);

  const load = useCallback(() => {
    SmsGateway.getStationProfile()
      .then(p => {
        setProfile(p);
        // A brand-new station's name defaults to its deviceId — show it blank so the operator names it.
        setStationName(p.name && p.name !== p.deviceId ? p.name : '');
      })
      .catch(() => {});
    SmsGateway.getServices().then(setServices).catch(() => {});
    SmsGateway.getSimConfigs().then(setSims).catch(() => {});
    SmsGateway.getSimHistory().then(setHistory).catch(() => {});
  }, []);

  const rescan = async () => {
    setRescanning(true);
    try {
      await SmsGateway.detectSimChanges();
      load();
    } finally {
      setRescanning(false);
    }
  };

  useFocusEffect(
    useCallback(() => {
      load();
    }, [load]),
  );

  const addService = async () => {
    const name = newService.trim();
    if (!name) return;
    setNewService('');
    // Show the chip immediately (optimistic) so it never feels like a no-op, then reconcile with
    // the DB. Skip if a service with this name is already present (the catalog is case-insensitive).
    setServices(prev =>
      prev.some(s => s.name.toLowerCase() === name.toLowerCase())
        ? prev
        : [...prev, { id: -Date.now(), name, enabled: true }],
    );
    try {
      await SmsGateway.addService(name);
    } catch {
      /* keep the optimistic chip; a failed write will simply not persist across reloads */
    }
    SmsGateway.getServices()
      .then(fetched => {
        if (fetched.length) setServices(fetched);
      })
      .catch(() => {});
  };

  const removeService = (svc: ServiceTag) => {
    Alert.alert('Remove service', `Delete "${svc.name}" from the catalog?`, [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Delete',
        style: 'destructive',
        onPress: async () => {
          await SmsGateway.deleteService(svc.id);
          // Also drop it from any SIM that had it assigned.
          setSims(prev => prev.map(s => ({ ...s, services: s.services.filter(n => n !== svc.name) })));
          SmsGateway.getServices().then(setServices).catch(() => {});
        },
      },
    ]);
  };

  const patchSim = (slot: number, patch: Partial<SimConfig>) =>
    setSims(prev => prev.map(s => (s.slot === slot ? { ...s, ...patch } : s)));

  const toggleSimService = (slot: number, name: string) =>
    setSims(prev =>
      prev.map(s =>
        s.slot === slot
          ? {
              ...s,
              services: s.services.includes(name)
                ? s.services.filter(n => n !== name)
                : [...s.services, name],
            }
          : s,
      ),
    );

  const saveAll = async () => {
    setSaving(true);
    try {
      await SmsGateway.setStationName(stationName.trim());
      for (const s of sims) {
        await SmsGateway.saveSimConfig({
          slot: s.slot,
          subscriptionId: s.subscriptionId,
          carrier: s.carrier,
          iccid: s.iccid,
          phoneNumber: s.phoneNumber,
          recordEnabled: s.recordEnabled,
          services: s.services,
        });
      }
      const synced = await SmsGateway.syncStationConfig();
      load();
      Alert.alert(
        'Saved',
        synced
          ? 'Station configuration saved and queued to the server.'
          : 'Saved on this device. Add a server webhook in Settings → Webhooks to sync it.',
      );
    } catch (e: any) {
      Alert.alert('Could not save', e?.message ?? 'Unknown error');
    } finally {
      setSaving(false);
    }
  };

  const enabledServices = services.filter(s => s.enabled);
  const recordingCount = sims.filter(s => s.recordEnabled).length;

  return (
    <Screen>
      <ScreenHeader
        title="Station setup"
        subtitle="Name, SIMs, services & changes — all in one place"
        right={<Badge label={`${recordingCount} ON`} color={recordingCount ? palette.accent : palette.textFaint} />}
      />

      <SectionLabel>Station</SectionLabel>
      <Card>
        <TextField
          label="Station name"
          value={stationName}
          onChangeText={setStationName}
          placeholder="e.g. Shop 1 — Counter"
          autoCapitalize="sentences"
          hint={profile ? `Device ID: ${profile.deviceId}` : undefined}
        />
      </Card>

      <SectionLabel>Service catalog</SectionLabel>
      <Card>
        <Text style={styles.help}>
          Add the payment services you handle (bKash, Nagad, …). Long-press a chip to remove it.
        </Text>
        <View style={styles.chipWrap}>
          {enabledServices.length === 0 && <Text style={styles.muted}>No services yet.</Text>}
          {enabledServices.map(svc => (
            <Chip key={svc.id} label={svc.name} onRemove={() => removeService(svc)} />
          ))}
        </View>
        <View style={styles.addRow}>
          <View style={{ flex: 1 }}>
            <TextField label="Add service" value={newService} onChangeText={setNewService} placeholder="service name" />
          </View>
          <Button title="Add" variant="secondary" onPress={addService} style={styles.addBtn} />
        </View>
      </Card>

      <SectionLabel>SIMs</SectionLabel>
      {sims.length === 0 && (
        <EmptyState
          title="No SIM detected"
          subtitle="Grant phone permission and insert a SIM, then reopen this screen."
        />
      )}

      {sims.map(sim => (
        <Card key={sim.slot}>
          <View style={styles.simHead}>
            <Text style={styles.simSlot}>SIM {sim.slot + 1}</Text>
            <Badge
              label={sim.detected ? sim.carrier ?? 'Active' : 'Not present'}
              color={sim.detected ? palette.info : palette.textFaint}
            />
          </View>

          <TextField
            label="Phone number"
            value={sim.phoneNumber ?? ''}
            onChangeText={t => patchSim(sim.slot, { phoneNumber: t })}
            placeholder="Android often can't auto-read this — type it"
            keyboardType="default"
            hint="This is the identity sent to the server for this line."
          />
          {!sim.phoneNumber && (
            <Text style={styles.numberHint}>
              {sim.phoneNumberSource === 'unavailable_permission'
                ? 'Blocked by permission — grant “Phone numbers” access, then re-scan below.'
                : 'Your carrier didn’t store the number on this SIM — type it above.'}
            </Text>
          )}

          <SwitchRow
            label="Record SMS from this SIM"
            description="When off, this SIM's messages are ignored entirely — never stored or forwarded."
            value={sim.recordEnabled}
            onValueChange={v => patchSim(sim.slot, { recordEnabled: v })}
          />

          {sim.recordEnabled && (
            <View style={styles.simServices}>
              <Text style={styles.assignLabel}>ASSIGNED SERVICES</Text>
              {enabledServices.length === 0 ? (
                <Text style={styles.muted}>Add a service in the catalog above first.</Text>
              ) : (
                <View style={styles.chipWrap}>
                  {enabledServices.map(svc => (
                    <Chip
                      key={svc.id}
                      label={svc.name}
                      active={sim.services.includes(svc.name)}
                      onPress={() => toggleSimService(sim.slot, svc.name)}
                    />
                  ))}
                </View>
              )}
            </View>
          )}
        </Card>
      ))}

      <Button
        title={saving ? 'Saving…' : 'Save & sync to server'}
        onPress={saveAll}
        loading={saving}
        style={{ marginTop: spacing.md }}
      />
      {!!profile?.syncedAt && (
        <Text style={styles.syncedAt}>Last synced {new Date(profile.syncedAt).toLocaleString()}</Text>
      )}

      <SectionLabel>SIM changes</SectionLabel>
      <Text style={styles.help}>
        Slot settings (recording + services) stay with the slot. When you swap a SIM, the number and
        carrier for that slot update automatically and re-sync to the server.
      </Text>
      <Button
        title={rescanning ? 'Scanning…' : 'Re-scan SIM state'}
        variant="secondary"
        onPress={rescan}
        loading={rescanning}
      />
      {history.length === 0 ? (
        <Text style={styles.muted}>No SIM changes recorded yet.</Text>
      ) : (
        history.map(ev => (
          <Card key={ev.id}>
            <View style={styles.evHead}>
              <Badge label={ev.event.toUpperCase()} color={palette.warn} />
              <Text style={styles.evTime}>{relativeTime(ev.timestamp)}</Text>
            </View>
            <Text style={styles.evDetail}>
              Slot {ev.slot} · {ev.carrier ?? 'unknown'} {ev.phoneNumber ? `· ${ev.phoneNumber}` : ''}
            </Text>
          </Card>
        ))
      )}
    </Screen>
  );
};

const styles = StyleSheet.create({
  help: { ...typography.body, color: palette.textMuted, fontSize: 13, marginBottom: spacing.md },
  muted: { ...typography.body, color: palette.textFaint, fontSize: 13 },
  chipWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  chip: {
    borderWidth: 1,
    borderRadius: radius.pill,
    paddingHorizontal: spacing.md,
    paddingVertical: 7,
  },
  chipIdle: { borderColor: palette.border, backgroundColor: palette.surfaceAlt },
  chipActive: { borderColor: palette.accent, backgroundColor: palette.accentSoft },
  chipText: { ...typography.label, color: palette.textMuted, fontSize: 12 },
  chipTextActive: { color: palette.accent },
  addRow: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm, marginTop: spacing.md },
  addBtn: { marginTop: 22, paddingHorizontal: spacing.lg },
  simHead: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: spacing.md },
  simSlot: { ...typography.title, color: palette.text },
  simServices: { marginTop: spacing.sm },
  assignLabel: { ...typography.label, color: palette.textFaint, marginBottom: spacing.sm },
  syncedAt: { ...typography.body, color: palette.textFaint, fontSize: 12, textAlign: 'center', marginTop: spacing.md },
  numberHint: { ...typography.body, color: palette.textFaint, fontSize: 12, marginTop: -spacing.xs, marginBottom: spacing.sm },
  evHead: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: spacing.sm },
  evTime: { ...typography.body, color: palette.textFaint, fontSize: 11 },
  evDetail: { ...typography.body, color: palette.textMuted },
});

export default StationSetupScreen;
