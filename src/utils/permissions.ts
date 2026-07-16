import { PermissionsAndroid, Platform } from 'react-native';

const REQUIRED: string[] = [
  'android.permission.RECEIVE_SMS',
  'android.permission.READ_SMS',
  'android.permission.READ_PHONE_STATE',
  'android.permission.READ_PHONE_NUMBERS',
];

const NOTIFICATIONS = 'android.permission.POST_NOTIFICATIONS';

export interface PermissionState {
  receiveSms: boolean;
  readSms: boolean;
  readPhoneState: boolean;
  postNotifications: boolean;
}

export async function requestCorePermissions(): Promise<PermissionState> {
  if (Platform.OS !== 'android') {
    return { receiveSms: false, readSms: false, readPhoneState: false, postNotifications: false };
  }
  const toRequest = [...REQUIRED];
  if (Platform.Version >= 33) toRequest.push(NOTIFICATIONS);

  const result = await PermissionsAndroid.requestMultiple(toRequest as any);
  const granted = (p: string) => result[p as keyof typeof result] === PermissionsAndroid.RESULTS.GRANTED;

  return {
    receiveSms: granted('android.permission.RECEIVE_SMS'),
    readSms: granted('android.permission.READ_SMS'),
    readPhoneState: granted('android.permission.READ_PHONE_STATE'),
    postNotifications: Platform.Version < 33 ? true : granted(NOTIFICATIONS),
  };
}

export async function checkCorePermissions(): Promise<PermissionState> {
  if (Platform.OS !== 'android') {
    return { receiveSms: false, readSms: false, readPhoneState: false, postNotifications: false };
  }
  const check = (p: string) => PermissionsAndroid.check(p as any);
  return {
    receiveSms: await check('android.permission.RECEIVE_SMS'),
    readSms: await check('android.permission.READ_SMS'),
    readPhoneState: await check('android.permission.READ_PHONE_STATE'),
    postNotifications: Platform.Version < 33 ? true : await check(NOTIFICATIONS),
  };
}
