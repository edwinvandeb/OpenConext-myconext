import {writable} from 'svelte/store';

export const user = writable({
    email: "",
    givenName: "",
    familyName: "",
    password: "",
    rememberMe: false,
    usePassword: false,
    createAccount: false
});