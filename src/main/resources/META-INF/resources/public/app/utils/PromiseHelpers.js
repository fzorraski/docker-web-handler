export const handleStatus = res =>
    res.ok ? res.json() : Promise.reject(res.statusText);

export const log = param => {
    return param;
}

export const requestInitPost = payload => {
    return {
                method: 'POST',
                    headers: {
                        'Content-type': 'application/json'
                    },
                body: payload
            }
}

export function responseWrapper(res){
    if(res.state === SUCCESS) return;

    if(res.state === ERROR_DELETE_DOCKER_IMAGE_IN_USE){
        alert('** Image in use ** \n'  + res.message);
    }else if(res.state === ERROR_DELETE_DOCKER_IMAGE_WITH_CHILD){
        alert('** Image has dependent child images ** \n' + res.message)
    }
}

const SUCCESS = 1
const ERROR_DELETE_DOCKER_IMAGE_IN_USE = 100
const ERROR_DELETE_DOCKER_IMAGE_WITH_CHILD = 101

