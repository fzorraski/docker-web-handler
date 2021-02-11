import {handleStatus, requestInitPost} from "../utils/PromiseHelpers.js";

const API = '/images/'


export function getDockerImages() {

    return fetch(API + 'list')
        .then(res => handleStatus(res))
        .catch(err => {
            console.log(err);
            return Promise.reject('Could not get the list of docker images');
        })
}

export function removeDockerImage(imageId) {
    const payload = JSON.stringify({
        imageId: imageId
    })

    return fetch(API + 'remove', requestInitPost(payload))
        .then(res => handleStatus(res))
        .finally(() => location.reload())
}
