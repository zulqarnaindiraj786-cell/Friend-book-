import { createRemoteJWKSet, jwtVerify, type JWTVerifyGetKey } from "npm:jose@5.9.6";
const project = "friendbook-17551";
// Resolver injection is for tests/server code only, never selectable through HTTP.
export function firebaseVerifier(keys: JWTVerifyGetKey) {
  return async (token: string): Promise<string> => {
    const {payload} = await jwtVerify(token, keys, {
      issuer: `https://securetoken.google.com/${project}`, audience: project, algorithms: ["RS256"], requiredClaims: ["exp", "iat", "sub"]
    });
    if (typeof payload.sub !== "string" || !/^[\w-]{1,256}$/.test(payload.sub)) throw Error("Invalid subject");
    return payload.sub;
  };
}
export const verifyFirebaseSession = firebaseVerifier(createRemoteJWKSet(new URL(
  "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com")));
